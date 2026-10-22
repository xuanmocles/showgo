package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.SeatReconcileLog;
import org.javaup.entity.ShowSeat;
import org.javaup.entity.ShowSession;
import org.javaup.entity.TicketOrder;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.ReconciliationStatus;
import org.javaup.enums.SessionStatus;
import org.javaup.enums.TicketOrderStatus;
import org.javaup.mapper.ShowSeatMapper;
import org.javaup.model.SeatTraceLogModel;
import org.javaup.model.SessionDetail;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeatService;
import org.javaup.service.IShowSessionService;
import org.javaup.service.ITicketOrderService;
import org.javaup.service.ITicketReconcileLogService;
import org.javaup.service.ITicketReconcileTaskService;
import org.javaup.constant.DistributedLockConstants;
import org.javaup.delay.message.DelayedSeatReleaseMessage;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 座位一致性对账任务。
 *
 * <p>处理两类差异：</p>
 * <ol>
 *   <li>Redis 有扣减流水但没有对应订单（Redis 扣了、DB 没单）→ 回滚座位；</li>
 *   <li>订单已创建但座位明细未落成已售（异步链路中断）→ 重建座位图并标记异常；</li>
 * </ol>
 * <p>同时兜底处理支付超时订单，防止延迟消息丢失导致座位被长期占用。</p>
 */
@Slf4j
@Service
public class TicketReconcileTaskServiceImpl implements ITicketReconcileTaskService {

    /** 允许的最终一致窗口：消息在途期间不判定为差异 */
    private static final long TOLERANCE_MILLIS = TimeUnit.SECONDS.toMillis(30);

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private ITicketOrderService ticketOrderService;

    @Resource
    private ITicketReconcileLogService ticketReconcileLogService;

    @Resource
    private ISeatService seatService;

    @Resource
    private ShowSeatMapper showSeatMapper;

    @Resource
    private RedisCache redisCache;

    @Resource
    private MeterRegistry meterRegistry;

    @Override
    @Scheduled(cron = "${ticket.reconcile.cron:0 */5 * * * ?}")
    public void reconciliationTaskExecute() {
        List<ShowSession> sessions = showSessionService.lambdaQuery()
                .in(ShowSession::getStatus, SessionStatus.ON_SALE.getCode(), SessionStatus.SOLD_OUT.getCode())
                .list();
        for (ShowSession session : sessions) {
            try {
                reconciliationTaskExecute(session.getId());
            } catch (Exception e) {
                log.error("场次对账失败 sessionId={}", session.getId(), e);
            }
        }
    }

    @Override
    @ServiceLock(lockType = LockType.Write, name = DistributedLockConstants.UPDATE_SESSION_SEAT_LOCK,
            keys = {"#sessionId"})
    public void reconciliationTaskExecute(Long sessionId) {
        SessionDetail detail = showSessionService.queryDetail(sessionId);
        int rollbackCount = reconcileRedisDeductWithoutOrder(detail);
        int abnormalCount = reconcileOrderWithSeatDetail(detail);
        int timeoutClosed = closeTimeoutOrders(detail);
        if (rollbackCount + abnormalCount + timeoutClosed > 0) {
            log.info("对账完成 sessionId={} 回滚={} 异常={} 超时关单={}",
                    sessionId, rollbackCount, abnormalCount, timeoutClosed);
            safeInc("ticket_reconcile_diff_total", rollbackCount + abnormalCount);
        }
    }

    /** 差异一：Redis 扣减流水存在、DB 订单不存在 */
    private int reconcileRedisDeductWithoutOrder(SessionDetail detail) {
        Long sessionId = detail.getSessionId();
        RedisKeyBuild traceKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.SEAT_TRACE_LOG_TAG_KEY, sessionId);
        Map<String, SeatTraceLogModel> traceLogs =
                redisCache.getAllMapForHash(traceKey, SeatTraceLogModel.class);
        if (CollectionUtil.isEmpty(traceLogs)) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int rollbackCount = 0;
        for (Map.Entry<String, SeatTraceLogModel> entry : traceLogs.entrySet()) {
            SeatTraceLogModel traceLog = entry.getValue();
            if (traceLog == null
                    || !String.valueOf(LogType.DEDUCT.getCode()).equals(traceLog.getLogType())) {
                continue;
            }
            if (traceLog.getTs() != null && now - traceLog.getTs() < TOLERANCE_MILLIS) {
                // 还在最终一致的容忍窗口内，跳过
                continue;
            }
            Long orderId = parseLong(traceLog.getOrderId());
            if (Objects.isNull(orderId)) {
                continue;
            }
            boolean orderExists = ticketOrderService.lambdaQuery()
                    .eq(TicketOrder::getId, orderId)
                    .exists();
            if (orderExists) {
                continue;
            }
            List<String> seatCodes = StrUtil.split(traceLog.getSeatCodes(), ',');
            List<Integer> seatIndexes = showSessionService.seatIndexesOf(sessionId, seatCodes);
            log.error("发现Redis扣减流水但无DB订单，回滚座位 sessionId={} orderId={} seats={}",
                    sessionId, orderId, traceLog.getSeatCodes());
            seatService.release(sessionId, parseLong(traceLog.getUserId()), seatIndexes,
                    orderId, BusinessType.FAIL);
            redisCache.delForHash(traceKey, entry.getKey());
            rollbackCount++;
        }
        if (rollbackCount > 0) {
            showSessionService.rebuildSeatMap(sessionId);
        }
        return rollbackCount;
    }

    /** 差异二：订单已创建，但座位明细没有落成已售 */
    private int reconcileOrderWithSeatDetail(SessionDetail detail) {
        Long sessionId = detail.getSessionId();
        List<TicketOrder> orders = ticketOrderService.lambdaQuery()
                .eq(TicketOrder::getSessionId, sessionId)
                .eq(TicketOrder::getReconciliationStatus, ReconciliationStatus.PENDING.getCode())
                .le(TicketOrder::getCreateTime,
                        LocalDateTimeUtil.offset(LocalDateTimeUtil.now(), -2, ChronoUnit.MINUTES))
                .list();
        int abnormal = 0;
        for (TicketOrder order : orders) {
            List<String> seatCodes = StrUtil.split(order.getSeatCodes(), ',');
            Long soldCount = showSeatMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowSeat>()
                            .eq(ShowSeat::getSessionId, sessionId)
                            .in(ShowSeat::getSeatCode, seatCodes)
                            .eq(ShowSeat::getOrderId, order.getId()));
            boolean consistent = soldCount != null && soldCount == seatCodes.size();
            markOrderStatus(order.getId(), consistent
                    ? ReconciliationStatus.CONSISTENT : ReconciliationStatus.ABNORMAL);
            if (!consistent) {
                abnormal++;
                log.error("订单座位明细不一致 orderId={} 期望={} 实际={}",
                        order.getId(), seatCodes.size(), soldCount);
                // 以数据库为准重建 Redis 座位图
                showSessionService.rebuildSeatMap(sessionId);
            }
        }
        return abnormal;
    }

    /** 差异三：延迟消息丢失导致支付超时订单未关闭 */
    private int closeTimeoutOrders(SessionDetail detail) {
        List<TicketOrder> timeoutOrders = ticketOrderService.lambdaQuery()
                .eq(TicketOrder::getSessionId, detail.getSessionId())
                .eq(TicketOrder::getStatus, TicketOrderStatus.WAIT_PAY.getCode())
                .le(TicketOrder::getExpireTime, LocalDateTimeUtil.now())
                .list();
        for (TicketOrder order : timeoutOrders) {
            log.warn("对账兜底关闭超时订单 orderId={}", order.getId());
            ticketOrderService.handleOrderPayTimeout(
                    new DelayedSeatReleaseMessage("ORDER", order.getSessionId(),
                            order.getUserId(), null, order.getId()));
        }
        return timeoutOrders.size();
    }

    @Transactional(rollbackFor = Exception.class)
    public void markOrderStatus(Long orderId, ReconciliationStatus status) {
        ticketOrderService.lambdaUpdate()
                .set(TicketOrder::getReconciliationStatus, status.getCode())
                .set(TicketOrder::getUpdateTime, LocalDateTime.now())
                .eq(TicketOrder::getId, orderId)
                .update();
        List<SeatReconcileLog> logs = ticketReconcileLogService.lambdaQuery()
                .eq(SeatReconcileLog::getOrderId, orderId)
                .list();
        for (SeatReconcileLog reconcileLog : logs) {
            ticketReconcileLogService.lambdaUpdate()
                    .set(SeatReconcileLog::getReconciliationStatus, status.getCode())
                    .set(SeatReconcileLog::getUpdateTime, LocalDateTime.now())
                    .eq(SeatReconcileLog::getId, reconcileLog.getId())
                    .update();
        }
    }

    private Long parseLong(String value) {
        try {
            return StrUtil.isBlank(value) ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void safeInc(String name, int count) {
        try {
            if (meterRegistry != null && count > 0) {
                meterRegistry.counter(name).increment(count);
            }
        } catch (Exception ignore) {
        }
    }
}

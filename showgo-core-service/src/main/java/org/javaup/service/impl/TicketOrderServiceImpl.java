package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.context.DelayQueueContext;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.delay.message.DelayedSeatReleaseMessage;
import org.javaup.dto.CreateTicketOrderDto;
import org.javaup.dto.PayTicketOrderDto;
import org.javaup.dto.TicketOrderOperateDto;
import org.javaup.dto.TicketOrderQueryDto;
import org.javaup.entity.LocalMessage;
import org.javaup.entity.SeatArea;
import org.javaup.entity.SeatLockLog;
import org.javaup.entity.ShowSeat;
import org.javaup.entity.Ticket;
import org.javaup.entity.TicketOrder;
import org.javaup.entity.TicketOrderRouter;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.SeatLockStatus;
import org.javaup.enums.SeatQuotaOperate;
import org.javaup.enums.SeatStatus;
import org.javaup.enums.TicketOrderStatus;
import org.javaup.enums.TicketStatus;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.kafka.message.TicketOrderMessage;
import org.javaup.kafka.redis.SeatRedisData;
import org.javaup.lua.SeatConfirmOperate;
import org.javaup.lua.SeatOperateResult;
import org.javaup.mapper.SeatLockLogMapper;
import org.javaup.mapper.ShowSeatMapper;
import org.javaup.mapper.TicketMapper;
import org.javaup.mapper.TicketOrderMapper;
import org.javaup.mapper.TicketOrderRouterMapper;
import org.javaup.message.MessageExtend;
import org.javaup.model.SessionDetail;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.repeatexecutelimit.annotion.RepeatExecuteLimit;
import org.javaup.service.ILocalMessageService;
import org.javaup.service.ISeatService;
import org.javaup.service.IShowSessionService;
import org.javaup.service.ITicketOrderService;
import org.javaup.service.ITicketReconcileLogService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.DELAY_SEAT_LOCK_RELEASE;
import static org.javaup.constant.Constant.TICKET_ORDER_TOPIC;
import static org.javaup.constant.RepeatExecuteLimitConstants.CREATE_TICKET_ORDER;

/**
 * 票务订单：负责「选座确认 → 落库 → 可靠投递 → 出票 → 退款回滚」的完整状态机。
 *
 * <p>关键链路：Redis 座位图先原子成交（速度与不超卖），随后在同一数据库事务里
 * 写订单、订单路由、本地消息表和对账流水；出票动作由本地消息表驱动的 Kafka 消费完成。</p>
 */
@Slf4j
@Service
public class TicketOrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder>
        implements ITicketOrderService {

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private ISeatService seatService;

    @Resource
    private ILocalMessageService localMessageService;

    @Resource
    private ITicketReconcileLogService ticketReconcileLogService;

    @Resource
    private SeatConfirmOperate seatConfirmOperate;

    @Resource
    private SeatRedisData seatRedisData;

    @Resource
    private TicketMapper ticketMapper;

    @Resource
    private TicketOrderRouterMapper ticketOrderRouterMapper;

    @Resource
    private ShowSeatMapper showSeatMapper;

    @Resource
    private SeatLockLogMapper seatLockLogMapper;

    @Resource
    private RedisCache redisCache;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private DelayQueueContext delayQueueContext;

    @Value("${ticket.order.pay-timeout-seconds:900}")
    private long payTimeoutSeconds;

    @Override
    public TicketOrder createOrder(CreateTicketOrderDto createTicketOrderDto) {
        Long userId = UserHolder.getUser().getId();
        Long sessionId = createTicketOrderDto.getSessionId();
        SessionDetail detail = showSessionService.queryDetail(sessionId);

        RedisKeyBuild tokenKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.SEAT_LOCK_TOKEN_TAG_KEY, sessionId, createTicketOrderDto.getLockToken());
        String lockedIndexes = redisCache.get(tokenKey, String.class);
        if (StrUtil.isBlank(lockedIndexes)) {
            throw new ShowGoFrameException(BaseCode.SEAT_LOCK_EXPIRED);
        }
        List<Integer> indexes = parseIndexes(lockedIndexes);
        List<String> seatCodes = showSessionService.seatCodesOf(sessionId, indexes);
        SeatArea area = resolveArea(detail, indexes.get(0));
        long amount = (area.getPrice() == null ? 0L : area.getPrice()) * indexes.size();

        long orderId = snowflakeIdGenerator.nextId();
        long traceId = snowflakeIdGenerator.nextId();
        String orderNo = buildOrderNo(orderId);
        String messageUuid = UUID.randomUUID().toString();

        // 1. Redis 座位图原子确认：锁定 -> 已售，并记录扣减流水
        SeatOperateResult confirmResult = confirmSeats(sessionId, userId, orderId, traceId,
                createTicketOrderDto.getLockToken(), indexes, seatCodes);
        if (!BaseCode.SUCCESS.getCode().equals(confirmResult.getCode())) {
            BaseCode baseCode = BaseCode.getRc(confirmResult.getCode());
            throw new ShowGoFrameException(baseCode == null ? "下单失败，请重新选座" : baseCode.getMsg());
        }

        TicketOrder order = new TicketOrder()
                .setId(orderId)
                .setOrderNo(orderNo)
                .setUserId(userId)
                .setShowId(detail.getShowId())
                .setSessionId(sessionId)
                .setTicketCount(indexes.size())
                .setAmount(amount)
                .setSeatCodes(StrUtil.join(",", seatCodes))
                .setStatus(TicketOrderStatus.WAIT_PAY.getCode())
                .setExpireTime(LocalDateTimeUtil.offset(LocalDateTimeUtil.now(), payTimeoutSeconds,
                        java.time.temporal.ChronoUnit.SECONDS))
                .setReconciliationStatus(org.javaup.enums.ReconciliationStatus.PENDING.getCode())
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());

        TicketOrderMessage message = new TicketOrderMessage(
                orderId, orderNo, userId, detail.getShowId(), sessionId, indexes, seatCodes, amount,
                confirmResult.getBeforeQty(), confirmResult.getChangeQty(), confirmResult.getAfterQty(), traceId);

        // 2. 数据库事务：订单 + 本地消息表 + 对账流水
        try {
            ((ITicketOrderService) AopContext.currentProxy())
                    .createOrderTx(order, traceId, JSON.toJSONString(message), messageUuid);
        } catch (Exception e) {
            // 事务失败必须把 Redis 座位还回去，否则会出现「座位已售但没有订单」
            log.error("订单落库失败，回滚 Redis 座位 orderId={}", orderId, e);
            seatRedisData.rollbackSeats(
                    SeatQuotaOperate.YES,
                    snowflakeIdGenerator.nextId(),
                    sessionId,
                    userId,
                    orderId,
                    StrUtil.join(",", seatCodes),
                    indexes,
                    confirmResult.getAfterQty(),
                    confirmResult.getChangeQty(),
                    confirmResult.getBeforeQty(),
                    showSessionService.resolveTtlSeconds(sessionId));
            throw e;
        }

        // 3. 登记支付超时关单
        delayQueueContext.sendMessage(
                SpringUtil.getPrefixDistinctionName() + "-" + DELAY_SEAT_LOCK_RELEASE,
                JSON.toJSONString(new DelayedSeatReleaseMessage("ORDER", sessionId, userId,
                        createTicketOrderDto.getLockToken(), orderId)),
                payTimeoutSeconds,
                TimeUnit.SECONDS);

        markLockOrdered(sessionId, createTicketOrderDto.getLockToken());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrderTx(TicketOrder order, Long traceId, String payload, String messageUuid) {
        save(order);
        ticketOrderRouterMapper.insert(new TicketOrderRouter()
                .setId(snowflakeIdGenerator.nextId())
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setSessionId(order.getSessionId())
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now()));
        LocalMessage localMessage = localMessageService.savePending(
                order.getId(),
                LocalMessageServiceImpl.BIZ_TYPE_TICKET_ORDER,
                SpringUtil.getPrefixDistinctionName() + "-" + TICKET_ORDER_TOPIC,
                payload,
                messageUuid);
        ticketReconcileLogService.saveReconcileLog(
                LogType.DEDUCT.getCode(),
                BusinessType.SUCCESS.getCode(),
                "order created",
                traceId,
                order.getSessionId(),
                order.getUserId(),
                order.getId(),
                order.getSeatCodes(),
                null,
                order.getTicketCount(),
                null);
        log.info("订单创建成功 orderId={} orderNo={} 待投递消息={}", order.getId(), order.getOrderNo(),
                localMessage.getMessageUuid());
    }

    @Override
    @RepeatExecuteLimit(name = CREATE_TICKET_ORDER, keys = {"#message.uuid"})
    @Transactional(rollbackFor = Exception.class)
    public void createTicketOrder(MessageExtend<TicketOrderMessage> message) {
        TicketOrderMessage body = message.getMessageBody();
        TicketOrder order = lambdaQuery()
                .eq(TicketOrder::getId, body.getOrderId())
                .eq(TicketOrder::getUserId, body.getUserId())
                .one();
        if (Objects.isNull(order)) {
            throw new ShowGoFrameException(BaseCode.TICKET_ORDER_NOT_EXIST);
        }
        // 幂等：座位明细已经关联到本订单，说明消费过了
        Long alreadySold = showSeatMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowSeat>()
                        .eq(ShowSeat::getSessionId, body.getSessionId())
                        .in(ShowSeat::getSeatCode, body.getSeatCodes())
                        .eq(ShowSeat::getOrderId, body.getOrderId()));
        if (alreadySold != null && alreadySold > 0) {
            log.info("座位明细已落库，跳过重复消费 orderId={}", body.getOrderId());
            return;
        }
        showSeatMapper.update(null, new LambdaUpdateWrapper<ShowSeat>()
                .set(ShowSeat::getStatus, SeatStatus.SOLD.getCode())
                .set(ShowSeat::getOrderId, body.getOrderId())
                .set(ShowSeat::getLockToken, null)
                .set(ShowSeat::getLockUserId, null)
                .set(ShowSeat::getUpdateTime, LocalDateTime.now())
                .eq(ShowSeat::getSessionId, body.getSessionId())
                .in(ShowSeat::getSeatCode, body.getSeatCodes()));
        showSessionService.lambdaUpdate()
                .setSql("seat_available = seat_available - " + body.getChangeQty())
                .eq(org.javaup.entity.ShowSession::getId, body.getSessionId())
                .update();
    }

    @Override
    public void markLocalMessageAcked(String messageUuid) {
        localMessageService.markAcked(messageUuid);
    }

    @Override
    public TicketOrder pay(PayTicketOrderDto payTicketOrderDto) {
        Long userId = UserHolder.getUser().getId();
        TicketOrder order = loadOrder(payTicketOrderDto.getOrderId(), userId);
        if (Objects.equals(order.getStatus(), TicketOrderStatus.ISSUED.getCode())
                || Objects.equals(order.getStatus(), TicketOrderStatus.PAID.getCode())) {
            return order;
        }
        if (!Objects.equals(order.getStatus(), TicketOrderStatus.WAIT_PAY.getCode())) {
            throw new ShowGoFrameException(BaseCode.TICKET_ORDER_STATUS_ILLEGAL);
        }
        return ((ITicketOrderService) AopContext.currentProxy()).payTx(order, payTicketOrderDto.getPayType());
    }

    @Transactional(rollbackFor = Exception.class)
    public TicketOrder payTx(TicketOrder order, Integer payType) {
        List<String> seatCodes = StrUtil.split(order.getSeatCodes(), ',');
        List<ShowSeat> seats = showSeatMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowSeat>()
                        .eq(ShowSeat::getSessionId, order.getSessionId())
                        .in(ShowSeat::getSeatCode, seatCodes));
        LocalDateTime now = LocalDateTimeUtil.now();
        for (ShowSeat seat : seats) {
            ticketMapper.insert(new Ticket()
                    .setId(snowflakeIdGenerator.nextId())
                    .setOrderId(order.getId())
                    .setUserId(order.getUserId())
                    .setShowId(order.getShowId())
                    .setSessionId(order.getSessionId())
                    .setSeatId(seat.getId())
                    .setSeatCode(seat.getSeatCode())
                    .setAreaName(seat.getAreaCode() + "区")
                    .setPrice(seat.getPrice())
                    .setStatus(TicketStatus.VALID.getCode())
                    .setCreateTime(now)
                    .setUpdateTime(now));
        }
        lambdaUpdate()
                .set(TicketOrder::getStatus, TicketOrderStatus.ISSUED.getCode())
                .set(TicketOrder::getPayType, payType)
                .set(TicketOrder::getPayTime, now)
                .set(TicketOrder::getUpdateTime, now)
                .eq(TicketOrder::getId, order.getId())
                .eq(TicketOrder::getUserId, order.getUserId())
                .update();
        order.setStatus(TicketOrderStatus.ISSUED.getCode()).setPayType(payType).setPayTime(now);
        log.info("订单支付出票完成 orderId={} 票数={}", order.getId(), seats.size());
        return order;
    }

    @Override
    public TicketOrder cancel(TicketOrderOperateDto operateDto) {
        Long userId = UserHolder.getUser().getId();
        TicketOrder order = loadOrder(operateDto.getOrderId(), userId);
        if (Objects.equals(order.getStatus(), TicketOrderStatus.CANCELED.getCode())) {
            return order;
        }
        if (!Objects.equals(order.getStatus(), TicketOrderStatus.WAIT_PAY.getCode())) {
            throw new ShowGoFrameException(BaseCode.TICKET_ORDER_STATUS_ILLEGAL);
        }
        return closeOrder(order, TicketOrderStatus.CANCELED, BusinessType.CANCEL,
                StrUtil.blankToDefault(operateDto.getReason(), "用户主动取消"));
    }

    @Override
    public TicketOrder refund(TicketOrderOperateDto operateDto) {
        Long userId = UserHolder.getUser().getId();
        TicketOrder order = loadOrder(operateDto.getOrderId(), userId);
        if (Objects.equals(order.getStatus(), TicketOrderStatus.REFUNDED.getCode())) {
            return order;
        }
        boolean refundable = Objects.equals(order.getStatus(), TicketOrderStatus.PAID.getCode())
                || Objects.equals(order.getStatus(), TicketOrderStatus.ISSUED.getCode());
        if (!refundable) {
            throw new ShowGoFrameException(BaseCode.TICKET_ORDER_STATUS_ILLEGAL);
        }
        return closeOrder(order, TicketOrderStatus.REFUNDED, BusinessType.REFUND,
                StrUtil.blankToDefault(operateDto.getReason(), "用户申请退票"));
    }

    @Override
    public void handleOrderPayTimeout(DelayedSeatReleaseMessage message) {
        TicketOrder order = lambdaQuery()
                .eq(TicketOrder::getId, message.getOrderId())
                .eq(TicketOrder::getUserId, message.getUserId())
                .one();
        if (Objects.isNull(order)) {
            return;
        }
        if (!Objects.equals(order.getStatus(), TicketOrderStatus.WAIT_PAY.getCode())) {
            // 已支付或已取消，无需处理
            return;
        }
        closeOrder(order, TicketOrderStatus.TIMEOUT, BusinessType.TIMEOUT, "支付超时自动关单");
    }

    @Override
    public TicketOrder getOrder(Long orderId) {
        return loadOrder(orderId, UserHolder.getUser().getId());
    }

    @Override
    public List<TicketOrder> myOrders(TicketOrderQueryDto queryDto) {
        Long userId = UserHolder.getUser().getId();
        Page<TicketOrder> page = new Page<>(queryDto.getPage(), queryDto.getPageSize());
        return lambdaQuery()
                .eq(TicketOrder::getUserId, userId)
                .eq(Objects.nonNull(queryDto.getStatus()), TicketOrder::getStatus, queryDto.getStatus())
                .orderByDesc(TicketOrder::getCreateTime)
                .page(page)
                .getRecords();
    }

    /**
     * 关单：取消 / 退票 / 支付超时共用。
     * 先落库状态，再释放 Redis 座位与座位明细，释放出的座位自动触发候补补位。
     */
    private TicketOrder closeOrder(TicketOrder order, TicketOrderStatus targetStatus,
                                   BusinessType businessType, String detail) {
        LocalDateTime now = LocalDateTimeUtil.now();
        int updated = lambdaUpdate()
                .set(TicketOrder::getStatus, targetStatus.getCode())
                .set(TicketOrder::getCloseTime, now)
                .set(TicketOrder::getRefundTime, targetStatus == TicketOrderStatus.REFUNDED ? now : null)
                .set(TicketOrder::getUpdateTime, now)
                .eq(TicketOrder::getId, order.getId())
                .eq(TicketOrder::getUserId, order.getUserId())
                .eq(TicketOrder::getStatus, order.getStatus())
                .update() ? 1 : 0;
        if (updated == 0) {
            // 并发下已被其它请求关闭，直接返回最新状态
            return loadOrder(order.getId(), order.getUserId());
        }
        if (targetStatus == TicketOrderStatus.REFUNDED) {
            ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                    .set(Ticket::getStatus, TicketStatus.REFUNDED.getCode())
                    .set(Ticket::getUpdateTime, now)
                    .eq(Ticket::getOrderId, order.getId()));
        }
        List<String> seatCodes = StrUtil.split(order.getSeatCodes(), ',');
        showSeatMapper.update(null, new LambdaUpdateWrapper<ShowSeat>()
                .set(ShowSeat::getStatus, SeatStatus.AVAILABLE.getCode())
                .set(ShowSeat::getOrderId, null)
                .set(ShowSeat::getUpdateTime, now)
                .eq(ShowSeat::getSessionId, order.getSessionId())
                .in(ShowSeat::getSeatCode, seatCodes));
        showSessionService.lambdaUpdate()
                .setSql("seat_available = seat_available + " + seatCodes.size())
                .eq(org.javaup.entity.ShowSession::getId, order.getSessionId())
                .update();

        List<Integer> indexes = showSessionService.seatIndexesOf(order.getSessionId(), seatCodes);
        seatService.release(order.getSessionId(), order.getUserId(), indexes, order.getId(), businessType);
        ticketReconcileLogService.saveReconcileLog(
                LogType.RESTORE.getCode(),
                businessType.getCode(),
                detail,
                snowflakeIdGenerator.nextId(),
                order.getSessionId(),
                order.getUserId(),
                order.getId(),
                order.getSeatCodes(),
                null,
                seatCodes.size(),
                null);
        markLockReleased(order.getSessionId(), order.getUserId());
        log.info("订单已关闭 orderId={} status={} reason={}", order.getId(), targetStatus.getMsg(), detail);
        return loadOrder(order.getId(), order.getUserId());
    }

    private SeatOperateResult confirmSeats(Long sessionId, Long userId, Long orderId, Long traceId,
                                           String lockToken, List<Integer> indexes, List<String> seatCodes) {
        List<String> keys = List.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_MAP_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_LOCK_HASH_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_LOCK_TOKEN_TAG_KEY, sessionId, lockToken).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_TRACE_LOG_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_STOCK_TAG_KEY, sessionId).getRelKey());
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(orderId));
        args.add(String.valueOf(userId));
        args.add(String.valueOf(traceId));
        args.add(String.valueOf(showSessionService.resolveTtlSeconds(sessionId)));
        args.add(String.valueOf(indexes.size()));
        args.add(StrUtil.join(",", seatCodes));
        indexes.forEach(index -> args.add(String.valueOf(index)));
        // 归属校验用的 lockToken：令牌 Key 的值存的是座位索引，令牌本身只能由调用方传入
        args.add(lockToken);
        return seatConfirmOperate.execute(keys, args.toArray(new String[0]));
    }

    private TicketOrder loadOrder(Long orderId, Long userId) {
        // 订单按 user_id 分片，带上分片键查询避免全路由
        TicketOrder order = lambdaQuery()
                .eq(TicketOrder::getId, orderId)
                .eq(TicketOrder::getUserId, userId)
                .one();
        if (Objects.isNull(order)) {
            throw new ShowGoFrameException(BaseCode.TICKET_ORDER_NOT_EXIST);
        }
        return order;
    }

    private SeatArea resolveArea(SessionDetail detail, Integer index) {
        for (SeatArea area : detail.getAreas()) {
            if (index >= area.getStartIndex() && index < area.getStartIndex() + area.getSeatCount()) {
                return area;
            }
        }
        throw new ShowGoFrameException(BaseCode.SEAT_NOT_EXIST);
    }

    private List<Integer> parseIndexes(String csv) {
        List<Integer> indexes = new ArrayList<>();
        if (CollectionUtil.isEmpty(StrUtil.split(csv, ','))) {
            return indexes;
        }
        for (String part : csv.split(",")) {
            if (StrUtil.isNotBlank(part)) {
                indexes.add(Integer.valueOf(part.trim()));
            }
        }
        return indexes;
    }

    private String buildOrderNo(Long orderId) {
        return LocalDateTimeUtil.format(LocalDateTimeUtil.now(), "yyyyMMddHHmmss")
                + String.valueOf(orderId).substring(String.valueOf(orderId).length() - 6);
    }

    private void markLockOrdered(Long sessionId, String lockToken) {
        try {
            seatLockLogMapper.update(null, new LambdaUpdateWrapper<SeatLockLog>()
                    .set(SeatLockLog::getStatus, SeatLockStatus.ORDERED.getCode())
                    .set(SeatLockLog::getUpdateTime, LocalDateTimeUtil.now())
                    .eq(SeatLockLog::getSessionId, sessionId)
                    .eq(SeatLockLog::getLockToken, lockToken));
        } catch (Exception e) {
            log.warn("更新锁座流水状态失败 sessionId={}", sessionId, e);
        }
    }

    private void markLockReleased(Long sessionId, Long userId) {
        try {
            seatLockLogMapper.update(null, new LambdaUpdateWrapper<SeatLockLog>()
                    .set(SeatLockLog::getStatus, SeatLockStatus.RELEASED.getCode())
                    .set(SeatLockLog::getReleaseTime, LocalDateTimeUtil.now())
                    .eq(SeatLockLog::getSessionId, sessionId)
                    .eq(SeatLockLog::getUserId, userId));
        } catch (Exception e) {
            log.warn("更新锁座流水状态失败 sessionId={} userId={}", sessionId, userId, e);
        }
    }
}

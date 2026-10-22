package org.javaup.kafka.consumer;

import cn.hutool.core.collection.CollectionUtil;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.core.RedisKeyManage;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.SeatQuotaOperate;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.kafka.message.SeatChangeMessage;
import org.javaup.kafka.message.TicketOrderMessage;
import org.javaup.kafka.producer.SeatChangeProducer;
import org.javaup.kafka.redis.SeatRedisData;
import org.javaup.message.MessageExtend;
import org.javaup.model.SessionDetail;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeatWaitlistService;
import org.javaup.service.IShowSessionService;
import org.javaup.service.ITicketOrderService;
import org.javaup.service.ITicketReconcileLogService;
import org.javaup.service.IWaitlistNotifyService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;
import static org.javaup.constant.Constant.TICKET_ORDER_TOPIC;

/**
 * 出票消费者：把「Redis 已售出的座位」落成订单与票，并维护余票、热销榜、候补补位。
 */
@Slf4j
@Component
public class TicketOrderConsumer extends AbstractConsumerHandler<TicketOrderMessage> {

    @Resource
    private ITicketOrderService ticketOrderService;

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private ISeatWaitlistService seatWaitlistService;

    @Resource
    private IWaitlistNotifyService waitlistNotifyService;

    @Resource
    private ITicketReconcileLogService ticketReconcileLogService;

    @Resource
    private SeatRedisData seatRedisData;

    @Resource
    private SeatChangeProducer seatChangeProducer;

    @Resource
    private RedisCache redisCache;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private MeterRegistry meterRegistry;

    /** 消息在 MQ 中滞留超过该阈值即认为链路异常，直接回滚 Redis 座位 */
    @Value("${ticket.order.message-delay-threshold-ms:10000}")
    private long messageDelayThresholdMs = 10000L;

    public TicketOrderConsumer() {
        super(TicketOrderMessage.class);
    }

    @KafkaListener(topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + TICKET_ORDER_TOPIC})
    public void onMessage(String value,
                          @Headers Map<String, Object> headers,
                          @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                          Acknowledgment acknowledgment) {
        consumeRaw(value, key, headers);
        if (acknowledgment != null) {
            acknowledgment.acknowledge();
        }
    }

    @Override
    protected Boolean beforeConsume(MessageExtend<TicketOrderMessage> message) {
        TicketOrderMessage body = message.getMessageBody();
        long delay = System.currentTimeMillis() - message.getProducerTime().getTime();
        if (delay <= messageDelayThresholdMs) {
            return true;
        }
        log.warn("出票消息延迟 {}ms 超过阈值 {}ms，回滚 Redis 座位。orderId={}",
                delay, messageDelayThresholdMs, body.getOrderId());
        Long traceId = snowflakeIdGenerator.nextId();
        seatRedisData.rollbackSeats(
                SeatQuotaOperate.YES,
                traceId,
                body.getSessionId(),
                body.getUserId(),
                body.getOrderId(),
                String.join(",", body.getSeatCodes()),
                body.getSeatIndexes(),
                body.getAfterQty(),
                body.getChangeQty(),
                body.getBeforeQty(),
                resolveSessionTtlSeconds(body.getSessionId()));
        try {
            ticketReconcileLogService.saveReconcileLog(LogType.RESTORE.getCode(),
                    BusinessType.TIMEOUT.getCode(),
                    "message delayed " + delay + "ms, seat rolled back",
                    traceId,
                    body.getSessionId(),
                    body.getUserId(),
                    body.getOrderId(),
                    String.join(",", body.getSeatCodes()),
                    body.getAfterQty(),
                    body.getChangeQty(),
                    body.getBeforeQty());
        } catch (Exception e) {
            log.warn("保存对账日志失败(消息延迟)", e);
        }
        safeInc("ticket_order_message_dropped");
        return false;
    }

    @Override
    protected void doConsume(MessageExtend<TicketOrderMessage> message) {
        ticketOrderService.createTicketOrder(message);
    }

    @Override
    protected void afterConsumeSuccess(MessageExtend<TicketOrderMessage> message) {
        super.afterConsumeSuccess(message);
        TicketOrderMessage body = message.getMessageBody();
        try {
            // 消息已被成功消费，本地消息表置为已确认
            ticketOrderService.markLocalMessageAcked(message.getUuid());
        } catch (Exception e) {
            log.warn("更新本地消息表状态失败 uuid={}", message.getUuid(), e);
        }
        try {
            seatChangeProducer.sendPayload(
                    org.javaup.core.SpringUtil.getPrefixDistinctionName() + "-"
                            + org.javaup.constant.Constant.SEAT_CHANGE_TOPIC,
                    new SeatChangeMessage(body.getSessionId(), body.getSeatIndexes(), 2, "SELL"));
        } catch (Exception e) {
            log.warn("广播座位变更失败 sessionId={}", body.getSessionId(), e);
        }
        try {
            // 出票成功后，用户不再需要候补
            seatWaitlistService.removeFromWaitlist(body.getSessionId(), body.getUserId());
        } catch (Exception e) {
            log.warn("清理候补记录失败", e);
        }
        try {
            updateHotRankAndVenueBuyers(body);
        } catch (Exception e) {
            log.warn("更新热销榜/场馆买家统计失败，不影响主流程", e);
        }
    }

    @Override
    protected void afterConsumeFailure(MessageExtend<TicketOrderMessage> message, Throwable throwable) {
        super.afterConsumeFailure(message, throwable);
        TicketOrderMessage body = message.getMessageBody();
        // 订单已存在属于幂等命中，此时座位的「已售」状态是正确的，不应回滚
        SeatQuotaOperate operate = SeatQuotaOperate.YES;
        if (throwable instanceof ShowGoFrameException frameException
                && Objects.nonNull(frameException.getCode())
                && frameException.getCode().equals(BaseCode.TICKET_ORDER_EXIST.getCode())) {
            operate = SeatQuotaOperate.NO;
        }
        Long traceId = snowflakeIdGenerator.nextId();
        seatRedisData.rollbackSeats(
                operate,
                traceId,
                body.getSessionId(),
                body.getUserId(),
                body.getOrderId(),
                String.join(",", body.getSeatCodes()),
                body.getSeatIndexes(),
                body.getAfterQty(),
                body.getChangeQty(),
                body.getBeforeQty(),
                resolveSessionTtlSeconds(body.getSessionId()));
        try {
            String detail = throwable == null ? "consume failed" : "consume failed: " + throwable.getMessage();
            ticketReconcileLogService.saveReconcileLog(LogType.RESTORE.getCode(),
                    BusinessType.FAIL.getCode(),
                    detail,
                    traceId,
                    body.getSessionId(),
                    body.getUserId(),
                    body.getOrderId(),
                    String.join(",", body.getSeatCodes()),
                    body.getAfterQty(),
                    body.getChangeQty(),
                    body.getBeforeQty());
        } catch (Exception e) {
            log.warn("保存对账日志失败(消费失败)", e);
        }
        safeInc("ticket_order_consume_failure");
    }

    private void updateHotRankAndVenueBuyers(TicketOrderMessage body) {
        redisCache.incrementScoreForSortedSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_HOT_RANK_KEY),
                String.valueOf(body.getSessionId()),
                body.getChangeQty() == null ? 1 : body.getChangeQty());

        SessionDetail detail = showSessionService.queryDetail(body.getSessionId());
        if (detail == null || detail.getVenueId() == null) {
            return;
        }
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        RedisKeyBuild dailyKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.VENUE_TOP_BUYERS_DAILY_TAG_KEY, detail.getVenueId(), day);
        redisCache.incrementScoreForSortedSet(dailyKey, String.valueOf(body.getUserId()),
                body.getChangeQty() == null ? 1 : body.getChangeQty());
        Long ttl = redisCache.getExpire(dailyKey, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            redisCache.expire(dailyKey, 90, TimeUnit.DAYS);
        }
    }

    private long resolveSessionTtlSeconds(Long sessionId) {
        try {
            SessionDetail detail = showSessionService.queryDetail(sessionId);
            if (detail != null && detail.getEndTime() != null) {
                long seconds = java.time.Duration.between(
                        cn.hutool.core.date.LocalDateTimeUtil.now(), detail.getEndTime()).getSeconds();
                return Math.max(1L, seconds + java.time.Duration.ofDays(1).getSeconds());
            }
        } catch (Exception e) {
            log.warn("解析场次TTL失败 sessionId={}", sessionId, e);
        }
        return TimeUnit.DAYS.toSeconds(7);
    }

    private void safeInc(String name) {
        try {
            if (meterRegistry != null) {
                meterRegistry.counter(name).increment();
            }
        } catch (Exception ignore) {
        }
    }
}

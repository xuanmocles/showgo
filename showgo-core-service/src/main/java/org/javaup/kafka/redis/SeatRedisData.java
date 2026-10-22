package org.javaup.kafka.redis;

import cn.hutool.core.collection.ListUtil;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.RollbackFailureLog;
import org.javaup.enums.BaseCode;
import org.javaup.enums.LogType;
import org.javaup.enums.SeatQuotaOperate;
import org.javaup.lua.SeatRollbackOperate;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IRollbackAlertService;
import org.javaup.service.IRollbackFailureLogService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 座位数据回滚组件：带指数退避重试，重试耗尽后落库并告警，避免「Redis 扣了但单没生成」长期悬空。
 */
@Slf4j
@Component
public class SeatRedisData {

    @Resource
    private SeatRollbackOperate seatRollbackOperate;

    @Resource
    private IRollbackFailureLogService rollbackFailureLogService;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private MeterRegistry meterRegistry;

    @Resource
    private IRollbackAlertService rollbackAlertService;

    @Value("${ticket.rollback.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${ticket.rollback.retry.initial-backoff-millis:200}")
    private long initialBackoffMillis;

    @Value("${ticket.rollback.retry.max-backoff-millis:1000}")
    private long maxBackoffMillis;

    /**
     * 回滚座位。
     *
     * @param operate  是否同时回收用户的购票额度
     * @param traceId  本次回滚的链路 id
     * @param orderId  订单 id，锁座超时场景可为 0
     * @param seatIndexes 座位索引
     * @param seatCodes 座位编码，逗号分隔
     */
    public void rollbackSeats(SeatQuotaOperate operate,
                              Long traceId,
                              Long sessionId,
                              Long userId,
                              Long orderId,
                              String seatCodes,
                              List<Integer> seatIndexes,
                              Integer beforeQty,
                              Integer changeQty,
                              Integer afterQty,
                              long ttlSeconds) {
        List<String> keys = ListUtil.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_MAP_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_LOCK_HASH_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_USER_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_TRACE_LOG_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_STOCK_TAG_KEY, sessionId).getRelKey());

        List<String> args = ListUtil.toList(
                String.valueOf(userId),
                String.valueOf(orderId == null ? 0L : orderId),
                String.valueOf(traceId),
                String.valueOf(operate.getCode()),
                String.valueOf(ttlSeconds),
                String.valueOf(seatIndexes.size()),
                seatCodes == null ? "" : seatCodes);
        for (Integer index : seatIndexes) {
            args.add(String.valueOf(index));
        }

        Integer finalCode = rollbackWithRetry(keys, args.toArray(new String[0]));
        boolean ok = finalCode != null && finalCode.equals(BaseCode.SUCCESS.getCode());
        if (!ok) {
            String reason = BaseCode.getMsg(finalCode == null ? -1 : finalCode);
            log.error("Redis座位回滚最终失败|sessionId={}|userId={}|orderId={}|traceId={}|reason={}",
                    sessionId, userId, orderId, traceId, reason);
            saveFailureLog(sessionId, userId, orderId, traceId,
                    "seat rollback failed after retries: " + reason, finalCode);
            safeInc("ticket_seat_rollback_give_up", "component", "seat_redis_data");
        }
    }

    private Integer rollbackWithRetry(List<String> keys, String[] args) {
        int attempt = 0;
        long backoff = Math.max(50, initialBackoffMillis);
        Integer lastCode = null;
        while (true) {
            try {
                Integer result = seatRollbackOperate.execute(keys, args);
                lastCode = result;
                if (result != null && result.equals(BaseCode.SUCCESS.getCode())) {
                    safeInc("ticket_seat_rollback_success", "component", "seat_redis_data");
                    return result;
                }
                log.warn("Redis座位回滚失败，准备重试|attempt={}|reason={}",
                        attempt + 1, BaseCode.getMsg(result == null ? -1 : result));
            } catch (Exception e) {
                lastCode = -1;
                log.warn("Redis座位回滚异常，准备重试|attempt={}|error={}", attempt + 1, e.getMessage());
            }
            attempt++;
            if (attempt >= retryMaxAttempts) {
                break;
            }
            sleepQuietly(withJitter(backoff));
            backoff = Math.min(backoff * 2, Math.max(backoff, maxBackoffMillis));
        }
        return lastCode;
    }

    private long withJitter(long base) {
        return base + Math.round(base * 0.15 * Math.random());
    }

    private void sleepQuietly(long backoffMillis) {
        try {
            TimeUnit.MILLISECONDS.sleep(backoffMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void saveFailureLog(Long sessionId, Long userId, Long orderId, Long traceId,
                                String detail, Integer resultCode) {
        try {
            RollbackFailureLog entity = new RollbackFailureLog()
                    .setId(snowflakeIdGenerator.nextId())
                    .setOrderId(orderId)
                    .setUserId(userId)
                    .setSessionId(sessionId)
                    .setDetail(detail)
                    .setResultCode(resultCode)
                    .setTraceId(traceId)
                    .setRetryAttempts(retryMaxAttempts)
                    .setSource("seat_redis_data")
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now());
            rollbackFailureLogService.save(entity);
            safeInc("ticket_seat_rollback_failure", "reason", "retry_exhausted");
            rollbackAlertService.sendRollbackAlert(entity);
        } catch (Exception e) {
            log.warn("保存回滚失败日志异常", e);
        }
    }

    private void safeInc(String name, String tagKey, String tagValue) {
        try {
            if (meterRegistry != null) {
                meterRegistry.counter(name, tagKey, tagValue).increment();
            }
        } catch (Exception ignore) {
        }
    }
}

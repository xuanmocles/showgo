package org.javaup.service.impl;

import com.alibaba.fastjson.JSON;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.RollbackFailureLog;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IRollbackAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 回滚失败告警：写 AUDIT 日志 + 打点，并按场次做去重，避免同一问题刷屏。
 */
@Slf4j
@Service
public class RollbackAlertServiceImpl implements IRollbackAlertService {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    @Resource
    private RedisCache redisCache;

    @Resource
    private MeterRegistry meterRegistry;

    @Override
    public void sendRollbackAlert(RollbackFailureLog failureLog) {
        try {
            if (failureLog.getSessionId() != null) {
                boolean first = redisCache.setIfAbsent(
                        RedisKeyBuild.createRedisKey(
                                RedisKeyManage.SEAT_LOCK_ALERT_DEDUP_KEY, failureLog.getSessionId()),
                        "1", 5, TimeUnit.MINUTES);
                if (!first) {
                    return;
                }
            }
            AUDIT_LOG.error("SEAT_ROLLBACK_ALERT | {}", JSON.toJSONString(failureLog));
            if (meterRegistry != null) {
                meterRegistry.counter("ticket_seat_rollback_alert").increment();
            }
        } catch (Exception e) {
            log.warn("发送回滚告警异常", e);
        }
    }
}

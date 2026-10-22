package org.javaup.ratelimit.extension;

import lombok.extern.slf4j.Slf4j;
import org.javaup.config.TicketRateLimitConfigProperties;
import org.javaup.core.RedisKeyManage;
import org.javaup.enums.BaseCode;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * @description: 基于阈值的临时封禁
 **/
@Slf4j
public class ThresholdPenaltyPolicy implements RateLimitPenaltyPolicy {

    private final RedisCache redisCache;
    private final TicketRateLimitConfigProperties props;

    public ThresholdPenaltyPolicy(RedisCache redisCache, TicketRateLimitConfigProperties props) {
        this.redisCache = redisCache;
        this.props = props;
    }

    @Override
    public void apply(RateLimitContext context, BaseCode reason) {
        try {
            if (reason == BaseCode.TICKET_RATE_LIMIT_IP_EXCEEDED) {
                applyForIp(context);
            } else if (reason == BaseCode.TICKET_RATE_LIMIT_USER_EXCEEDED) {
                applyForUser(context);
            }
        } catch (Exception e) {
            log.debug("Penalty policy apply failed: {}", e.getMessage());
        }
    }

    private void applyForIp(RateLimitContext ctx) {
        Long sessionId = ctx.getSessionId();
        String clientIp = ctx.getClientIp();
        if (Objects.isNull(sessionId) || Objects.isNull(clientIp)) {
            return;
        }
        RedisKeyBuild violationKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_VIOLATION_IP_TAG_KEY, sessionId, clientIp);
        long count = redisCache.incrBy(violationKey, 1L);
        if (count == 1L) {
            redisCache.expire(violationKey, props.getViolationWindowSeconds(), TimeUnit.SECONDS);
        }
        if (count >= props.getIpBlockThreshold()) {
            RedisKeyBuild blockKey = RedisKeyBuild.createRedisKey(
                    RedisKeyManage.TICKET_BLOCK_IP_TAG_KEY, sessionId, clientIp);
            redisCache.set(blockKey, "1", props.getIpBlockTtlSeconds(), TimeUnit.SECONDS);
            log.warn("Temporary banned IP: sessionId={}, ip={}, ttlSeconds={}, violationCount={}",
                    sessionId, clientIp, props.getIpBlockTtlSeconds(), count);
        }
    }

    private void applyForUser(RateLimitContext ctx) {
        Long sessionId = ctx.getSessionId();
        Long userId = ctx.getUserId();
        if (Objects.isNull(sessionId) || Objects.isNull(userId)) {
            return;
        }
        RedisKeyBuild violationKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_VIOLATION_USER_TAG_KEY, sessionId, userId);
        long count = redisCache.incrBy(violationKey, 1L);
        if (count == 1L) {
            redisCache.expire(violationKey, props.getViolationWindowSeconds(), TimeUnit.SECONDS);
        }
        if (count >= props.getUserBlockThreshold()) {
            RedisKeyBuild blockKey = RedisKeyBuild.createRedisKey(
                    RedisKeyManage.TICKET_BLOCK_USER_TAG_KEY, sessionId, userId);
            redisCache.set(blockKey, "1", props.getUserBlockTtlSeconds(), TimeUnit.SECONDS);
            log.warn("Temporary banned user: sessionId={}, userId={}, ttlSeconds={}, violationCount={}",
                    sessionId, userId, props.getUserBlockTtlSeconds(), count);
        }
    }
}
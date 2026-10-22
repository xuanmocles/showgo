package org.javaup.service.impl;

import cn.hutool.core.util.IdUtil;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.lua.SeatAccessTokenOperate;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ITicketAccessTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 选座访问令牌实现：Redis 保存 + Lua 原子校验并消费。
 */
@Slf4j
@Service
public class TicketAccessTokenServiceImpl implements ITicketAccessTokenService {

    @Value("${ticket.access.token.enabled:true}")
    private boolean enabled;

    @Value("${ticket.access.token.ttl-seconds:30}")
    private long ttlSeconds;

    @Resource
    private RedisCache redisCache;

    @Resource
    private MeterRegistry meterRegistry;

    private SeatAccessTokenOperate operate;

    @PostConstruct
    public void init() {
        operate = new SeatAccessTokenOperate(redisCache);
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String issueAccessToken(Long sessionId, Long userId) {
        String token = IdUtil.simpleUUID();
        RedisKeyBuild key = RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_ACCESS_TOKEN_TAG_KEY, sessionId, userId);
        boolean ok = redisCache.setIfAbsent(key, token, ttlSeconds, TimeUnit.SECONDS);
        if (!ok) {
            String existed = redisCache.get(key, String.class);
            safeInc("ticket_access_token_issue_conflict");
            return existed != null ? existed : token;
        }
        safeInc("ticket_access_token_issue_success");
        return token;
    }

    @Override
    public boolean validateAndConsume(Long sessionId, Long userId, String token) {
        String key = RedisKeyBuild.createRedisKey(
                RedisKeyManage.TICKET_ACCESS_TOKEN_TAG_KEY, sessionId, userId).getRelKey();
        boolean success = operate.validateAndConsume(key, token);
        safeInc(success ? "ticket_access_token_consume_success" : "ticket_access_token_consume_fail");
        return success;
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

package org.javaup.cache;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.kafka.message.ShowCacheInvalidationMessage;
import org.javaup.kafka.producer.ShowCacheInvalidationProducer;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.springframework.stereotype.Component;

import static org.javaup.constant.Constant.SHOW_CACHE_INVALIDATION_TOPIC;

/**
 * 场次缓存失效入口：本地缓存直接失效，Redis 缓存删除，并通过 Kafka 广播给其它实例。
 *
 * <p>消费组带随机后缀，所以每个实例都会收到广播，各自的 L1 本地缓存都能被清掉。</p>
 */
@Slf4j
@Component
public class SessionCacheInvalidator {

    @Resource
    private RedisCache redisCache;

    @Resource
    private SessionLocalCache sessionLocalCache;

    @Resource
    private ShowCacheInvalidationProducer showCacheInvalidationProducer;

    public void publishInvalidate(Long sessionId, String reason) {
        RedisKeyBuild cacheKey = RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SESSION_KEY, sessionId);
        sessionLocalCache.invalidate(cacheKey.getRelKey());
        redisCache.del(cacheKey);
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SESSION_NULL_KEY, sessionId));
        try {
            showCacheInvalidationProducer.sendPayload(
                    SpringUtil.getPrefixDistinctionName() + "-" + SHOW_CACHE_INVALIDATION_TOPIC,
                    new ShowCacheInvalidationMessage(sessionId, reason));
        } catch (Exception e) {
            log.warn("广播场次缓存失效失败 sessionId={}", sessionId, e);
        }
    }
}

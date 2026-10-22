package org.javaup.kafka.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.cache.SessionLocalCache;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.core.RedisKeyManage;
import org.javaup.kafka.message.ShowCacheInvalidationMessage;
import org.javaup.message.MessageExtend;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

import static org.javaup.constant.Constant.SHOW_CACHE_INVALIDATION_TOPIC;
import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;
import static org.javaup.constant.DistributedLockConstants.UPDATE_SESSION_SEAT_LOCK;

/**
 * 场次缓存失效广播消费者：清掉本实例的 L1 缓存与 Redis 缓存。
 */
@Slf4j
@Component
public class ShowCacheInvalidationConsumer extends AbstractConsumerHandler<ShowCacheInvalidationMessage> {

    @Resource
    private SessionLocalCache sessionLocalCache;

    @Resource
    private RedisCache redisCache;

    @Resource
    private MeterRegistry meterRegistry;

    public ShowCacheInvalidationConsumer() {
        super(ShowCacheInvalidationMessage.class);
    }

    @KafkaListener(
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + SHOW_CACHE_INVALIDATION_TOPIC},
            groupId = "${prefix.distinction.name:showgo}-session-cache-invalidation-${random.uuid}")
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
    protected void doConsume(MessageExtend<ShowCacheInvalidationMessage> message) {
        ShowCacheInvalidationMessage body = message.getMessageBody();
        if (Objects.isNull(body) || Objects.isNull(body.getSessionId())) {
            return;
        }
        invalidate(body.getSessionId());
    }

    @ServiceLock(lockType = LockType.Write, name = UPDATE_SESSION_SEAT_LOCK, keys = {"#sessionId"})
    public void invalidate(Long sessionId) {
        RedisKeyBuild cacheKey = RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SESSION_KEY, sessionId);
        sessionLocalCache.invalidate(cacheKey.getRelKey());
        redisCache.del(cacheKey);
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SESSION_NULL_KEY, sessionId));
        if (meterRegistry != null) {
            meterRegistry.counter("ticket_session_cache_invalidation").increment();
        }
    }
}

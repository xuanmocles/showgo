package org.javaup.kafka.consumer;

import com.alibaba.fastjson.JSON;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.kafka.message.ShowCacheInvalidationMessage;
import org.javaup.message.MessageExtend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.javaup.constant.Constant.SHOW_CACHE_INVALIDATION_TOPIC;
import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 缓存失效广播的死信队列：反复失败的消息落到这里人工排查，避免阻塞正常消费。
 */
@Slf4j
@Component
public class ShowCacheInvalidationDlqConsumer extends AbstractConsumerHandler<ShowCacheInvalidationMessage> {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    @Resource
    private MeterRegistry meterRegistry;

    public ShowCacheInvalidationDlqConsumer() {
        super(ShowCacheInvalidationMessage.class);
    }

    @KafkaListener(
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + SHOW_CACHE_INVALIDATION_TOPIC + ".DLQ"},
            groupId = "${prefix.distinction.name:showgo}-session-cache-invalidation-dlq-${random.uuid}")
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
        AUDIT_LOG.error("SESSION_CACHE_INVALIDATION_DLQ | message={}", JSON.toJSONString(message));
        if (meterRegistry != null) {
            meterRegistry.counter("ticket_session_cache_invalidation_dlq").increment();
        }
    }
}

package org.javaup.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.javaup.AbstractProducerHandler;
import org.javaup.kafka.message.ShowCacheInvalidationMessage;
import org.javaup.message.MessageExtend;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 场次缓存失效广播生产者。
 */
@Slf4j
@Component
public class ShowCacheInvalidationProducer extends AbstractProducerHandler<MessageExtend<ShowCacheInvalidationMessage>> {

    public ShowCacheInvalidationProducer(KafkaTemplate<String, MessageExtend<ShowCacheInvalidationMessage>> kafkaTemplate) {
        super(kafkaTemplate);
    }
}

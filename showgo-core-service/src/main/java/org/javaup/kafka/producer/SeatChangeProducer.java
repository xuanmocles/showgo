package org.javaup.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.javaup.AbstractProducerHandler;
import org.javaup.kafka.message.SeatChangeMessage;
import org.javaup.message.MessageExtend;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 座位变更广播生产者。
 */
@Slf4j
@Component
public class SeatChangeProducer extends AbstractProducerHandler<MessageExtend<SeatChangeMessage>> {

    public SeatChangeProducer(KafkaTemplate<String, MessageExtend<SeatChangeMessage>> kafkaTemplate) {
        super(kafkaTemplate);
    }
}

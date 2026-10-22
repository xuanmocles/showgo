package org.javaup.kafka.producer;

import lombok.extern.slf4j.Slf4j;
import org.javaup.AbstractProducerHandler;
import org.javaup.kafka.message.TicketOrderMessage;
import org.javaup.message.MessageExtend;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 出票消息生产者。
 */
@Slf4j
@Component
public class TicketOrderProducer extends AbstractProducerHandler<MessageExtend<TicketOrderMessage>> {

    public TicketOrderProducer(KafkaTemplate<String, MessageExtend<TicketOrderMessage>> kafkaTemplate) {
        super(kafkaTemplate);
    }
}

package org.javaup.kafka.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.consumer.AbstractConsumerHandler;
import org.javaup.kafka.message.SeatChangeMessage;
import org.javaup.message.MessageExtend;
import org.javaup.stream.SeatChangeBroadcaster;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

import static org.javaup.constant.Constant.SEAT_CHANGE_TOPIC;
import static org.javaup.constant.Constant.SPRING_INJECT_PREFIX_DISTINCTION_NAME;

/**
 * 座位变更广播消费者：把变更推给订阅了该场次座位图的前端连接。
 */
@Slf4j
@Component
public class SeatChangeConsumer extends AbstractConsumerHandler<SeatChangeMessage> {

    @Resource
    private SeatChangeBroadcaster seatChangeBroadcaster;

    @Resource
    private MeterRegistry meterRegistry;

    public SeatChangeConsumer() {
        super(SeatChangeMessage.class);
    }

    @KafkaListener(
            topics = {SPRING_INJECT_PREFIX_DISTINCTION_NAME + "-" + SEAT_CHANGE_TOPIC},
            groupId = "${prefix.distinction.name:showgo}-seat-change-${random.uuid}")
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
    protected void doConsume(MessageExtend<SeatChangeMessage> message) {
        SeatChangeMessage body = message.getMessageBody();
        if (Objects.isNull(body) || Objects.isNull(body.getSessionId())) {
            return;
        }
        seatChangeBroadcaster.broadcast(body.getSessionId(), body);
        if (meterRegistry != null) {
            meterRegistry.counter("ticket_seat_change_broadcast", "type",
                    body.getChangeType() == null ? "UNKNOWN" : body.getChangeType()).increment();
        }
    }
}

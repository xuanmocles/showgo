package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.LocalMessage;
import org.javaup.enums.LocalMessageStatus;
import org.javaup.kafka.message.TicketOrderMessage;
import org.javaup.kafka.producer.TicketOrderProducer;
import org.javaup.mapper.LocalMessageMapper;
import org.javaup.message.MessageExtend;
import org.javaup.service.ILocalMessageService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * 本地消息表（Outbox）：解决「订单已落库但消息没发出去」的问题。
 *
 * <p>下单事务里同时写入订单与待投递消息，投递器扫描后发往 Kafka，
 * 发送失败的按下一次重试时间退避，超过最大次数进入 FAILED 等待人工介入。</p>
 */
@Slf4j
@Service
public class LocalMessageServiceImpl extends ServiceImpl<LocalMessageMapper, LocalMessage>
        implements ILocalMessageService {

    /** 本地消息表业务类型 */
    public static final String BIZ_TYPE_TICKET_ORDER = "TICKET_ORDER";

    @Resource
    private TicketOrderProducer ticketOrderProducer;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private MeterRegistry meterRegistry;

    @Value("${ticket.local-message.max-retry:6}")
    private int maxRetry;

    @Override
    public LocalMessage savePending(Long bizId, String bizType, String topic, String payload, String messageUuid) {
        LocalMessage message = new LocalMessage()
                .setId(snowflakeIdGenerator.nextId())
                .setMessageUuid(messageUuid)
                .setBizId(bizId)
                .setBizType(bizType)
                .setTopic(topic)
                .setPayload(payload)
                .setStatus(LocalMessageStatus.PENDING.getCode())
                .setRetryCount(0)
                .setNextRetryTime(LocalDateTimeUtil.now())
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        save(message);
        return message;
    }

    @Override
    public void markSent(Long id) {
        lambdaUpdate()
                .set(LocalMessage::getStatus, LocalMessageStatus.SENT.getCode())
                .set(LocalMessage::getUpdateTime, LocalDateTimeUtil.now())
                .eq(LocalMessage::getId, id)
                .update();
        safeInc("ticket_local_message_sent");
    }

    @Override
    public void markAcked(String messageUuid) {
        lambdaUpdate()
                .set(LocalMessage::getStatus, LocalMessageStatus.ACKED.getCode())
                .set(LocalMessage::getUpdateTime, LocalDateTimeUtil.now())
                .eq(LocalMessage::getMessageUuid, messageUuid)
                .update();
    }

    @Override
    @Scheduled(fixedDelayString = "${ticket.local-message.scan-interval-ms:2000}")
    public int dispatchPending() {
        List<LocalMessage> pending = lambdaQuery()
                .in(LocalMessage::getStatus, LocalMessageStatus.PENDING.getCode(), LocalMessageStatus.FAILED.getCode())
                .le(LocalMessage::getNextRetryTime, LocalDateTimeUtil.now())
                .orderByAsc(LocalMessage::getCreateTime)
                .last("limit 200")
                .list();
        if (CollectionUtil.isEmpty(pending)) {
            return 0;
        }
        int sent = 0;
        for (LocalMessage message : pending) {
            try {
                dispatch(message);
                markSent(message.getId());
                sent++;
            } catch (Exception e) {
                onDispatchFailure(message, e);
            }
        }
        return sent;
    }

    private void dispatch(LocalMessage message) {
        if (BIZ_TYPE_TICKET_ORDER.equals(message.getBizType())) {
            TicketOrderMessage body = JSON.parseObject(message.getPayload(), TicketOrderMessage.class);
            MessageExtend<TicketOrderMessage> extend = MessageExtend.of(body);
            // 复用本地消息的 uuid 与创建时间：消费端据此做幂等与延迟判定
            extend.setUuid(message.getMessageUuid());
            extend.setProducerTime(Date.from(message.getCreateTime()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant()));
            ticketOrderProducer.sendRecord(message.getTopic(), extend);
            return;
        }
        throw new IllegalStateException("未支持的消息类型: " + message.getBizType());
    }

    private void onDispatchFailure(LocalMessage message, Exception e) {
        int retryCount = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
        boolean giveUp = retryCount >= maxRetry;
        LocalMessage update = new LocalMessage()
                .setId(message.getId())
                .setRetryCount(retryCount)
                .setStatus(giveUp ? LocalMessageStatus.FAILED.getCode() : LocalMessageStatus.PENDING.getCode())
                .setNextRetryTime(LocalDateTimeUtil.offset(LocalDateTimeUtil.now(),
                        Math.min(60L, (long) Math.pow(2, retryCount)), java.time.temporal.ChronoUnit.SECONDS))
                .setErrorMsg(cn.hutool.core.util.StrUtil.maxLength(String.valueOf(e.getMessage()), 500))
                .setUpdateTime(LocalDateTimeUtil.now());
        updateById(update);
        log.warn("本地消息投递失败 uuid={} retry={} giveUp={}", message.getMessageUuid(), retryCount, giveUp, e);
        safeInc(giveUp ? "ticket_local_message_dispatch_give_up" : "ticket_local_message_dispatch_retry");
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

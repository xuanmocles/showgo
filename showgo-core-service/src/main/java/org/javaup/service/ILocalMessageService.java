package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.entity.LocalMessage;

public interface ILocalMessageService extends IService<LocalMessage> {

    /**
     * 在业务事务内登记一条待投递消息，保证「业务落库成功 = 消息一定被投递」。
     */
    LocalMessage savePending(Long bizId, String bizType, String topic, String payload, String messageUuid);

    /** 标记投递成功 */
    void markSent(Long id);

    /** 标记消费确认 */
    void markAcked(String messageUuid);

    /** 扫描并投递待发送消息 */
    int dispatchPending();
}

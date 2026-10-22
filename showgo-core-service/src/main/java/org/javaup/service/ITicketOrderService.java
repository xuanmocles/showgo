package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.delay.message.DelayedSeatReleaseMessage;
import org.javaup.dto.CreateTicketOrderDto;
import org.javaup.dto.PayTicketOrderDto;
import org.javaup.dto.TicketOrderOperateDto;
import org.javaup.dto.TicketOrderQueryDto;
import org.javaup.entity.TicketOrder;
import org.javaup.kafka.message.TicketOrderMessage;
import org.javaup.message.MessageExtend;

import java.util.List;

public interface ITicketOrderService extends IService<TicketOrder> {

    /** 下单：用锁座凭证换取订单，座位由锁定转为已售 */
    TicketOrder createOrder(CreateTicketOrderDto createTicketOrderDto);

    /** 支付并出票 */
    TicketOrder pay(PayTicketOrderDto payTicketOrderDto);

    /** 支付事务：真正扣状态、生成票 */
    TicketOrder payTx(TicketOrder order, Integer payType);

    /** 取消未支付订单 */
    TicketOrder cancel(TicketOrderOperateDto operateDto);

    /** 退票（已支付/已出票） */
    TicketOrder refund(TicketOrderOperateDto operateDto);

    TicketOrder getOrder(Long orderId);

    List<TicketOrder> myOrders(TicketOrderQueryDto queryDto);

    /** 订单事务：订单 + 路由 + 本地消息表 + 对账流水，同一事务提交 */
    void createOrderTx(TicketOrder order, Long traceId, String payload, String messageUuid);

    /** 出票消费：座位明细落库、场次余票扣减 */
    void createTicketOrder(MessageExtend<TicketOrderMessage> message);

    /** 消费成功后把本地消息置为已确认 */
    void markLocalMessageAcked(String messageUuid);

    /** 支付超时关单 */
    void handleOrderPayTimeout(DelayedSeatReleaseMessage message);
}

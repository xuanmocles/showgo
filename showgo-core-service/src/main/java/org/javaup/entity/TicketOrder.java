package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 票务订单。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket_order")
public class TicketOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private String orderNo;

    private Long userId;

    private Long showId;

    private Long sessionId;

    private Integer ticketCount;

    /** 订单金额，单位分 */
    private Long amount;

    /** 座位编码，逗号分隔 */
    private String seatCodes;

    /** 1待支付 2已支付 3已出票 4已取消 5已退款 6已超时 */
    private Integer status;

    private Integer payType;

    private LocalDateTime expireTime;

    private LocalDateTime payTime;

    private LocalDateTime closeTime;

    private LocalDateTime refundTime;

    /** 对账状态：1待处理 2异常 3不一致 4一致 */
    private Integer reconciliationStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

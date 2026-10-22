package org.javaup.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 取消 / 退票 / 查询单个订单。
 */
@Data
public class TicketOrderOperateDto {

    @NotNull(message = "订单不能为空")
    private Long orderId;

    /** 取消或退票原因 */
    private String reason;
}

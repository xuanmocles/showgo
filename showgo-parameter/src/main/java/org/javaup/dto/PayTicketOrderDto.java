package org.javaup.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 支付订单（示例项目用同步调用模拟支付网关回调）。
 */
@Data
public class PayTicketOrderDto {

    @NotNull(message = "订单不能为空")
    private Long orderId;

    /** 1余额 2支付宝 3微信 */
    private Integer payType = 1;
}

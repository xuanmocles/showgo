package org.javaup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建订单：用锁座凭证换取订单，座位从「锁定」进入「已售」。
 */
@Data
public class CreateTicketOrderDto {

    @NotNull(message = "场次不能为空")
    private Long sessionId;

    @NotBlank(message = "锁座凭证不能为空")
    private String lockToken;
}

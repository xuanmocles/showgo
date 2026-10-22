package org.javaup.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 我的订单查询。
 */
@Data
public class TicketOrderQueryDto {

    /** 订单状态，为空表示全部 */
    private Integer status;

    @Min(1)
    private Integer page = 1;

    @Min(1)
    @Max(20)
    private Integer pageSize = 5;
}

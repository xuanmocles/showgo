package org.javaup.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 锁座请求：一次锁定同一分区同一排的若干连续座位。
 */
@Data
public class SeatLockDto {

    @NotNull(message = "场次不能为空")
    private Long sessionId;

    /** 座位索引（分区内从 0 开始的连续编号） */
    @NotEmpty(message = "请至少选择一个座位")
    @Size(max = 6, message = "单笔订单最多选择 6 个座位")
    private List<Integer> seatIndexes;
}

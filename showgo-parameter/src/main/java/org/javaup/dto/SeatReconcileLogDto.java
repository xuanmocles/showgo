package org.javaup.dto;

import lombok.Data;

/**
 * 对账流水写入参数。
 */
@Data
public class SeatReconcileLogDto {

    private Long orderId;

    private Long sessionId;

    private Long userId;

    private String seatCodes;

    private Long traceId;

    /** -1扣减 1恢复 */
    private Integer logType;

    private Integer businessType;

    private Integer beforeQty;

    private Integer changeQty;

    private Integer afterQty;

    private String detail;
}

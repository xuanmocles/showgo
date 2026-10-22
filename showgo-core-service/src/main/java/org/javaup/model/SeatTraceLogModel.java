package org.javaup.model;

import lombok.Data;

import java.io.Serializable;

/**
 * Redis 座位变更流水：与 DB 对账流水一一对应，用于发现「Redis 扣了、DB 没单」的中间态。
 */
@Data
public class SeatTraceLogModel implements Serializable {

    private static final long serialVersionUID = 1L;

    private String logType;

    private Long ts;

    private String orderId;

    private String traceId;

    private String userId;

    private String seatCodes;

    private Integer beforeQty;

    private Integer changeQty;

    private Integer afterQty;
}

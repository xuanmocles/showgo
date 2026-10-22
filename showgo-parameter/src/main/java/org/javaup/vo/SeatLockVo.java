package org.javaup.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 锁座结果。
 */
@Data
public class SeatLockVo {

    private Long sessionId;

    private String lockToken;

    private List<String> seatCodes;

    /** 订单金额，单位分 */
    private Long amount;

    private LocalDateTime expireTime;
}

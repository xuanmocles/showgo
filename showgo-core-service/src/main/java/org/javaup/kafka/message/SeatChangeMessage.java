package org.javaup.kafka.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 座位变更广播：多实例与前端实时刷新座位图。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatChangeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;

    private List<Integer> seatIndexes;

    /** 0可售 1锁定 2已售 3不可用 */
    private Integer status;

    /** LOCK / SELL / RELEASE / REFUND */
    private String changeType;
}

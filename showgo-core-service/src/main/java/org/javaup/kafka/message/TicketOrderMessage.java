package org.javaup.kafka.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 出票消息：选座确认后异步落库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;

    private String orderNo;

    private Long userId;

    private Long showId;

    private Long sessionId;

    /** 座位索引 */
    private List<Integer> seatIndexes;

    /** 座位编码，顺序与 seatIndexes 一致 */
    private List<String> seatCodes;

    private Long amount;

    private Integer beforeQty;

    private Integer changeQty;

    private Integer afterQty;

    private Long traceId;
}

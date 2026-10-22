package org.javaup.delay.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 延迟释放消息：锁座超时释放座位、订单支付超时关单。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DelayedSeatReleaseMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** LOCK 锁座超时 / ORDER 支付超时 */
    private String type;

    private Long sessionId;

    private Long userId;

    private String lockToken;

    private Long orderId;
}

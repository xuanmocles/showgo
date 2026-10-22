package org.javaup.constant;

/**
 * 幂等业务名。
 */
public class RepeatExecuteLimitConstants {

    /** 出票消费幂等，幂等键为消息 uuid */
    public static final String CREATE_TICKET_ORDER = "create_ticket_order";

    /** 锁座幂等，幂等键为「场次 + 用户 + 座位组合」 */
    public static final String LOCK_SEAT = "lock_seat";
}

package org.javaup.constant;

/**
 * 全局常量：中间件命名前缀、Kafka topic、布隆过滤器业务标识。
 */
public class Constant {

    public static final String PREFIX_DISTINCTION_NAME = "prefix.distinction.name";

    public static final String DEFAULT_PREFIX_DISTINCTION_NAME = "showgo";

    public static final String SPRING_INJECT_PREFIX_DISTINCTION_NAME =
            "${" + PREFIX_DISTINCTION_NAME + ":" + DEFAULT_PREFIX_DISTINCTION_NAME + "}";

    /** 出票消息：选座成功后异步落库、生成票 */
    public static final String TICKET_ORDER_TOPIC = "ticket_order_topic";

    /** 座位变更广播：多实例刷新本地座位图、前端实时刷新 */
    public static final String SEAT_CHANGE_TOPIC = "seat_change_topic";

    /** 演出/场次缓存失效广播 */
    public static final String SHOW_CACHE_INVALIDATION_TOPIC = "show_cache_invalidation_topic";

    /** 延迟队列：锁座超时释放 + 订单支付超时关单（消费者按消息 type 区分） */
    public static final String DELAY_SEAT_LOCK_RELEASE = "delay_seat_lock_release";

    /** 延迟队列：开售前提醒 */
    public static final String DELAY_PRESALE_REMINDER = "delay_presale_reminder";

    public static final String BLOOM_FILTER_HANDLER_SHOW = "show";

    public static final String BLOOM_FILTER_HANDLER_SESSION = "session";
}

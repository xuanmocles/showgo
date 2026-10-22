package org.javaup.core;

import lombok.Getter;

/**
 * 全站 Redis Key 规范。
 *
 * <p>带 <code>{}</code> 的是 Redis Cluster 的 HashTag：同一场次下的座位图、锁座、库存、流水
 * 会被路由到同一个槽位，Lua 脚本才能在一次调用里原子操作多个 key。</p>
 */
@Getter
public enum RedisKeyManage {

    /* ---------------- 演出 / 场次 / 场馆 缓存 ---------------- */
    USER_INFO_KEY("user:info:%s", "用户id", "UserInfo 对象"),

    CACHE_SHOW_KEY("cache:show:%s", "演出id", "Show 对象"),

    CACHE_SHOW_NULL_KEY("cache:show_null:%s", "演出id", "空值占位"),

    CACHE_SESSION_KEY("cache:session:{%s}", "场次id（同槽位HashTag）", "SessionDetail 对象"),

    CACHE_SESSION_NULL_KEY("cache:session_null:{%s}", "场次id（同槽位HashTag）", "空值占位"),

    CACHE_VENUE_KEY("cache:venue:%s", "场馆id", "Venue 对象"),

    CACHE_VENUE_NULL_KEY("cache:venue_null:%s", "场馆id", "空值占位"),

    /* ---------------- 座位图与锁座 ---------------- */
    SEAT_MAP_TAG_KEY("ticket:seat:map:{%s}", "场次id（同槽位HashTag）", "BitField 座位图，每座位 2bit"),

    SEAT_LOCK_HASH_TAG_KEY("ticket:seat:lock:{%s}", "场次id（同槽位HashTag）", "Hash：座位索引 -> 锁座令牌"),

    SEAT_LOCK_TOKEN_TAG_KEY("ticket:seat:token:{%s}:%s", "场次id（同槽位HashTag）与锁座令牌", "锁定的座位索引列表"),

    SEAT_USER_TAG_KEY("ticket:seat:user:{%s}", "场次id（同槽位HashTag）", "Hash：用户id -> 该场次已锁定+已购张数"),

    SEAT_TRACE_LOG_TAG_KEY("ticket:seat:trace:{%s}", "场次id（同槽位HashTag）", "Hash：traceId -> 座位变更流水"),

    SEAT_INDEX_CODE_TAG_KEY("ticket:seat:index:code:{%s}", "场次id（同槽位HashTag）", "Hash：座位索引 -> 座位编码"),

    SESSION_STOCK_TAG_KEY("ticket:session:stock:{%s}", "场次id（同槽位HashTag）", "场次剩余座位数"),

    SESSION_HOT_RANK_KEY("ticket:session:hot:rank", "全局热销榜", "ZSet，member 为场次id，score 为销量"),

    /* ---------------- 限流与风控 ---------------- */
    TICKET_LIMIT_IP_TAG_KEY("ticket:limit:ip:{%s}:%s", "场次id（同槽位HashTag）与IP", "按IP的限流计数"),

    TICKET_LIMIT_USER_TAG_KEY("ticket:limit:user:{%s}:%s", "场次id（同槽位HashTag）与用户id", "按用户的限流计数"),

    TICKET_LIMIT_IP_SW_TAG_KEY("ticket:limit:ip:sw:{%s}:%s", "场次id（同槽位HashTag）与IP", "按IP的滑动窗口计数"),

    TICKET_LIMIT_USER_SW_TAG_KEY("ticket:limit:user:sw:{%s}:%s", "场次id（同槽位HashTag）与用户id", "按用户的滑动窗口计数"),

    TICKET_LIMIT_IP_TB_TAG_KEY("ticket:limit:ip:tb:{%s}:%s", "场次id（同槽位HashTag）与IP", "按IP的令牌桶 Hash(tokens/last_ms)"),

    TICKET_LIMIT_USER_TB_TAG_KEY("ticket:limit:user:tb:{%s}:%s", "场次id（同槽位HashTag）与用户id", "按用户的令牌桶 Hash(tokens/last_ms)"),

    TICKET_BLOCK_IP_TAG_KEY("ticket:block:ip:{%s}:%s", "场次id（同槽位HashTag）与IP", "临时封禁标记"),

    TICKET_BLOCK_USER_TAG_KEY("ticket:block:user:{%s}:%s", "场次id（同槽位HashTag）与用户id", "临时封禁标记"),

    TICKET_VIOLATION_IP_TAG_KEY("ticket:violation:ip:{%s}:%s", "场次id（同槽位HashTag）与IP", "违规计数"),

    TICKET_VIOLATION_USER_TAG_KEY("ticket:violation:user:{%s}:%s", "场次id（同槽位HashTag）与用户id", "违规计数"),

    TICKET_ACCESS_TOKEN_TAG_KEY("ticket:access:token:{%s}:%s", "场次id（同槽位HashTag）与用户id", "选座访问令牌"),

    /* ---------------- 订单 ---------------- */
    DB_TICKET_ORDER_KEY("db:ticket:order:%s", "订单id", "TicketOrder 对象，用于下单后短时间回查"),

    /* ---------------- 候补排队 ---------------- */
    SEAT_WAITLIST_ZSET_TAG_KEY("ticket:waitlist:zset:{%s}", "场次id（同槽位HashTag）", "ZSet，member 为用户id，score 为候补时间戳"),

    SEAT_WAITLIST_STATUS_TAG_KEY("ticket:waitlist:status:{%s}", "场次id（同槽位HashTag）", "Hash：用户id -> 候补状态"),

    SEAT_WAITLIST_NOTIFY_DEDUP_KEY("ticket:waitlist:notify:dedup:{%s}:%s", "场次id（同槽位HashTag）与用户id", "候补通知去重标记"),

    /* ---------------- 运营与通知 ---------------- */
    VENUE_TOP_BUYERS_DAILY_TAG_KEY("ticket:venue:topbuyers:daily:{%s}:%s", "场馆id（同槽位HashTag）与日期(yyyyMMdd)", "ZSet，member 为用户id，score 为购票数"),

    VENUE_TOP_BUYERS_UNION_TAG_KEY("ticket:venue:topbuyers:union:{%s}:%s", "场馆id（同槽位HashTag）与聚合范围", "临时 ZSet"),

    PRESALE_REMINDER_DEDUP_KEY("ticket:presale:reminder:dedup:{%s}:%s", "场次id（同槽位HashTag）与用户id", "开售提醒去重标记"),

    SEAT_LOCK_ALERT_DEDUP_KEY("ticket:seat:lock:alert:dedup:%s", "场次id", "锁座释放告警去重标记"),

    USER_LEVEL_MEMBERS_TAG_KEY("ticket:user:level:{%s}:members", "用户等级（同槽位HashTag）", "Set，member 为用户id"),

    USER_LEVEL_MEMBERS_UNION_TAG_KEY("ticket:user:level:union:{%s}", "用户等级范围标签", "临时 Set"),

    /* ---------------- 管理端 ---------------- */
    ADMIN_TOKEN_KEY("admin:token:%s", "管理端登录令牌", "管理员用户名"),
    ;

    private final String key;

    private final String keyIntroduce;

    private final String valueIntroduce;

    RedisKeyManage(String key, String keyIntroduce, String valueIntroduce) {
        this.key = key;
        this.keyIntroduce = keyIntroduce;
        this.valueIntroduce = valueIntroduce;
    }
}

package org.javaup.utils;

/**
 * 直接使用 StringRedisTemplate 的 Key 与缓存时长常量。
 * 业务缓存 Key 统一由 {@link org.javaup.core.RedisKeyManage} 维护。
 */
public class RedisConstants {

    public static final String LOGIN_CODE_KEY = "login:code:";

    public static final Long LOGIN_CODE_TTL = 2L;

    public static final String LOGIN_USER_KEY = "login:token:";

    public static final Long LOGIN_USER_TTL = 36000L;

    /** 空值缓存时长（分钟） */
    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOW_TTL = 30L;

    public static final Long CACHE_SESSION_TTL = 30L;

    public static final Long CACHE_VENUE_TTL = 60L;

    /** 场馆地理坐标集合 */
    public static final String VENUE_GEO_KEY = "ticket:venue:geo";

    /** 场次座位图重建锁 */
    public static final String LOCK_SESSION_SEAT_KEY = "lock_session_seat";

    /** 演出缓存重建锁 */
    public static final String LOCK_SHOW_KEY = "lock_show";
}

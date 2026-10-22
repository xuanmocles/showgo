package org.javaup.cache;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.javaup.model.SessionDetail;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * L1 本地缓存：场次聚合详情。座位图这种强一致数据不放本地，直接走 Redis BitField。
 * 缓存项 TTL 跟随场次结束时间，避免「演出都结束了本地还留着旧状态」。
 */
@Component
public class SessionLocalCache {

    private final Cache<String, SessionDetail> cache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfter(new Expiry<String, SessionDetail>() {
                @Override
                public long expireAfterCreate(String key, SessionDetail value, long currentTime) {
                    long ttlSeconds = 60L;
                    if (value != null && value.getEndTime() != null) {
                        ttlSeconds = Math.max(
                                LocalDateTimeUtil.between(LocalDateTimeUtil.now(), value.getEndTime()).getSeconds(),
                                1L);
                    }
                    return TimeUnit.NANOSECONDS.convert(ttlSeconds, TimeUnit.SECONDS);
                }

                @Override
                public long expireAfterUpdate(String key, SessionDetail value, long currentTime, long currentDuration) {
                    return currentDuration;
                }

                @Override
                public long expireAfterRead(String key, SessionDetail value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    public SessionDetail get(String key) {
        return cache.getIfPresent(key);
    }

    public void put(String key, SessionDetail detail) {
        if (key != null && detail != null) {
            cache.put(key, detail);
        }
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }
}

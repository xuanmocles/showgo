package org.javaup.lua;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.redis.RedisCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 锁座脚本：校验座位状态与限购，原子置为锁定态。
 */
@Slf4j
@Component
public class SeatLockOperate {

    @Resource
    private RedisCache redisCache;

    private DefaultRedisScript<Long> redisScript;

    @PostConstruct
    public void init() {
        redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seatLock.lua")));
        redisScript.setResultType(Long.class);
    }

    public Integer execute(List<String> keys, String[] args) {
        Object object = redisCache.getInstance().execute(redisScript, keys, args);
        return object == null ? -1 : ((Number) object).intValue();
    }
}

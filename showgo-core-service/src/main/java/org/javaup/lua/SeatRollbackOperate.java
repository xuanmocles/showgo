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
 * 座位回滚脚本：把座位恢复为可售，并回收余票与用户额度。
 */
@Slf4j
@Component
public class SeatRollbackOperate {

    @Resource
    private RedisCache redisCache;

    private DefaultRedisScript<Long> redisScript;

    @PostConstruct
    public void init() {
        redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seatRollback.lua")));
        redisScript.setResultType(Long.class);
    }

    public Integer execute(List<String> keys, String[] args) {
        Object object = redisCache.getInstance().execute(redisScript, keys, args);
        if (object == null) {
            return null;
        }
        if (object instanceof Number) {
            return ((Number) object).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(object));
        } catch (Exception e) {
            log.warn("座位回滚脚本返回值无法解析: {}", object);
            return null;
        }
    }
}

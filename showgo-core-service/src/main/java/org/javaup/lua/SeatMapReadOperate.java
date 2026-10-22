package org.javaup.lua;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.redis.RedisCache;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 座位图读取脚本：一次往返取出整场座位状态。
 */
@Slf4j
@Component
public class SeatMapReadOperate {

    @Resource
    private RedisCache redisCache;

    private DefaultRedisScript<String> redisScript;

    @PostConstruct
    public void init() {
        redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seatMapRead.lua")));
        redisScript.setResultType(String.class);
    }

    public String execute(String seatMapKey, int seatTotal) {
        Object object = redisCache.getInstance().execute(redisScript,
                Collections.singletonList(seatMapKey), String.valueOf(seatTotal));
        return object == null ? "" : String.valueOf(object);
    }
}

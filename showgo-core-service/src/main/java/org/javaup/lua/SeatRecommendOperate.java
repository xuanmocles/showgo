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
import java.util.List;

/**
 * 连座推荐脚本：一次 GET 取出位图后在 Lua 内解码扫描，避免 N 次往返。
 */
@Slf4j
@Component
public class SeatRecommendOperate {

    @Resource
    private RedisCache redisCache;

    private DefaultRedisScript<String> redisScript;

    @PostConstruct
    public void init() {
        redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seatRecommend.lua")));
        redisScript.setResultType(String.class);
    }

    public String execute(String seatMapKey, int startIndex, int endIndex, int need) {
        Object object = redisCache.getInstance().execute(
                redisScript,
                Collections.singletonList(seatMapKey),
                String.valueOf(startIndex),
                String.valueOf(endIndex),
                String.valueOf(need));
        return object == null ? "" : String.valueOf(object);
    }
}

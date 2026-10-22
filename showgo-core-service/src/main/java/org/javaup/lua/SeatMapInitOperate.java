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
 * 座位图初始化脚本：Redis 数据丢失或首次开售时，按数据库状态重建位图。
 */
@Slf4j
@Component
public class SeatMapInitOperate {

    @Resource
    private RedisCache redisCache;

    private DefaultRedisScript<Long> redisScript;

    @PostConstruct
    public void init() {
        redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seatMapInit.lua")));
        redisScript.setResultType(Long.class);
    }

    public void execute(String seatMapKey, int seatTotal, List<String> indexStatusPairs) {
        List<String> args = new java.util.ArrayList<>(indexStatusPairs.size() + 1);
        args.add(String.valueOf(seatTotal));
        args.addAll(indexStatusPairs);
        redisCache.getInstance().execute(redisScript, Collections.singletonList(seatMapKey),
                args.toArray(new String[0]));
    }
}

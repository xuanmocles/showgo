package org.javaup.lua;

import com.alibaba.fastjson.JSON;
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
 * 下单确认脚本：座位由锁定转已售，扣减余票并写扣减流水。
 */
@Slf4j
@Component
public class SeatConfirmOperate {

    @Resource
    private RedisCache redisCache;

    private DefaultRedisScript<String> redisScript;

    @PostConstruct
    public void init() {
        redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/seatConfirm.lua")));
        redisScript.setResultType(String.class);
    }

    public SeatOperateResult execute(List<String> keys, String[] args) {
        Object object = redisCache.getInstance().execute(redisScript, keys, args);
        if (object == null) {
            SeatOperateResult result = new SeatOperateResult();
            result.setCode(-1);
            return result;
        }
        return JSON.parseObject(String.valueOf(object), SeatOperateResult.class);
    }
}

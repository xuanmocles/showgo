package org.javaup.utils;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.javaup.core.RedisKeyManage;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端鉴权：校验 Redis 中的 admin token。
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final RedisCache redisCache;

    public AdminAuthInterceptor(RedisCache redisCache) {
        this.redisCache = redisCache;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("Authorization");
        if (StrUtil.isBlank(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        Boolean exists = redisCache.hasKey(
                RedisKeyBuild.createRedisKey(RedisKeyManage.ADMIN_TOKEN_KEY, token));
        if (!Boolean.TRUE.equals(exists)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}

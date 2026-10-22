package org.javaup.config;

import jakarta.annotation.Resource;
import org.javaup.redis.RedisCache;
import org.javaup.utils.AdminAuthInterceptor;
import org.javaup.utils.LoginInterceptor;
import org.javaup.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器配置：公开的浏览类接口放行，下单/选座/我的票务都需要登录态。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisCache redisCache;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 管理端独立鉴权：/admin/** 走 Redis token，不走用户登录态
        registry.addInterceptor(new AdminAuthInterceptor(redisCache))
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/login")
                .order(1);

        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/show/**",
                        "/venue/**",
                        "/seat/map/**",
                        "/seat/stream/**",
                        "/admin/**",
                        "/user/code",
                        "/user/login"
                ).order(2);
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
    }
}

package org.javaup.execute;

import jakarta.servlet.http.HttpServletRequest;
import org.javaup.config.TicketRateLimitConfigProperties;
import org.javaup.core.RedisKeyManage;
import org.javaup.enums.BaseCode;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.lua.SlidingRateLimitOperate;
import org.javaup.lua.TokenBucketRateLimitOperate;
import org.javaup.ratelimit.extension.RateLimitContext;
import org.javaup.ratelimit.extension.RateLimitEventListener;
import org.javaup.ratelimit.extension.RateLimitPenaltyPolicy;
import org.javaup.ratelimit.extension.RateLimitScene;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @description: 限流执行 接口实现
 **/
public class RedisRateLimitHandler implements RateLimitHandler {

    private final TicketRateLimitConfigProperties ticketRateLimitConfigProperties;
    private final RedisCache redisCache;
    private final SlidingRateLimitOperate slidingRateLimitOperate;
    private final TokenBucketRateLimitOperate tokenBucketRateLimitOperate;
    private final RateLimitEventListener rateLimitEventListener;
    private final RateLimitPenaltyPolicy rateLimitPenaltyPolicy;

    public RedisRateLimitHandler(TicketRateLimitConfigProperties ticketRateLimitConfigProperties,
                                 RedisCache redisCache,
                                 SlidingRateLimitOperate slidingRateLimitOperate,
                                 TokenBucketRateLimitOperate tokenBucketRateLimitOperate,
                                 RateLimitEventListener rateLimitEventListener,
                                 RateLimitPenaltyPolicy rateLimitPenaltyPolicy) {
        this.ticketRateLimitConfigProperties = ticketRateLimitConfigProperties;
        this.redisCache = redisCache;
        this.slidingRateLimitOperate = slidingRateLimitOperate;
        this.tokenBucketRateLimitOperate = tokenBucketRateLimitOperate;
        this.rateLimitEventListener = rateLimitEventListener;
        this.rateLimitPenaltyPolicy = rateLimitPenaltyPolicy;
    }

    @Override
    public void execute(Long sessionId,
                        Long userId,
                        RateLimitScene scene) {
        String clientIp = resolveClientIp();

        if (isWhitelisted(userId, clientIp)) {
            return;
        }
        checkBans(sessionId, userId, clientIp);
        int ipLimitWindowMillis = resolveIpWindow(scene);
        int ipLimitMaxAttempts = resolveIpMaxAttempts(scene);
        int userLimitWindowMillis = resolveUserWindow(scene);
        int userLimitMaxAttempts = resolveUserMaxAttempts(scene);
        boolean useSliding = resolveSliding();
        List<String> keys = buildRateLimitKeys(sessionId, userId, clientIp, useSliding);
        String[] args = buildArgs(ipLimitWindowMillis, ipLimitMaxAttempts, userLimitWindowMillis, userLimitMaxAttempts);

        RateLimitContext ctx = buildContext(sessionId, userId, clientIp, keys, useSliding,
                ipLimitWindowMillis, ipLimitMaxAttempts, userLimitWindowMillis, userLimitMaxAttempts);
        safeBeforeExecute(ctx);
        Integer result = executeLua(useSliding, keys, args);
        ctx.setResult(result);
        handleResult(ctx);
    }
    
    private int resolveIpWindow(RateLimitScene scene) {
        TicketRateLimitConfigProperties.EndpointLimit ep = 
                scene == RateLimitScene.ISSUE_TOKEN 
                        ? 
                        ticketRateLimitConfigProperties.getIssue() 
                        : 
                        ticketRateLimitConfigProperties.getBook();
        
        Integer v = 
                ep != null
                        ? 
                        ep.getIpWindowMillis() 
                        : 
                        null;
        
        return v != null 
                ? 
                v 
                : 
                ticketRateLimitConfigProperties.getIpWindowMillis();
    }
    private int resolveIpMaxAttempts(RateLimitScene scene) {
        TicketRateLimitConfigProperties.EndpointLimit ep = 
                scene == RateLimitScene.ISSUE_TOKEN 
                        ? 
                        ticketRateLimitConfigProperties.getIssue() 
                        : 
                        ticketRateLimitConfigProperties.getBook();
        
        Integer v = 
                ep != null 
                        ? 
                        ep.getIpMaxAttempts() 
                        : 
                        null;
        return v != null 
                ? 
                v 
                : 
                ticketRateLimitConfigProperties.getIpMaxAttempts();
    }
    private int resolveUserWindow(RateLimitScene scene) {
        TicketRateLimitConfigProperties.EndpointLimit ep = 
                scene == RateLimitScene.ISSUE_TOKEN 
                        ? 
                        ticketRateLimitConfigProperties.getIssue() 
                        : 
                        ticketRateLimitConfigProperties.getBook();
        
        Integer v = 
                ep != null 
                        ? 
                        ep.getUserWindowMillis() 
                        : 
                        null;
        
        return v != null 
                ? 
                v 
                : 
                ticketRateLimitConfigProperties.getUserWindowMillis();
    }
    private int resolveUserMaxAttempts(RateLimitScene scene) {
        TicketRateLimitConfigProperties.EndpointLimit ep = 
                scene == RateLimitScene.ISSUE_TOKEN 
                        ? 
                        ticketRateLimitConfigProperties.getIssue() 
                        : 
                        ticketRateLimitConfigProperties.getBook();
        
        Integer v = 
                ep != null 
                        ? 
                        ep.getUserMaxAttempts() 
                        : 
                        null;
        
        return v != null 
                ? 
                v 
                : 
                ticketRateLimitConfigProperties.getUserMaxAttempts();
    }

    private boolean resolveSliding() {
        return ticketRateLimitConfigProperties.getEnableSlidingWindow();
    }
    
    private String resolveClientIp(){
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                String[] parts = xff.split(",");
                if (parts.length > 0) {
                    String ip = parts[0].trim();
                    if (!ip.isEmpty()) {
                        return ip;
                    }
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isEmpty()) {
                return realIp;
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isWhitelisted(Long userId, 
                                  String clientIp) {
        try {
            return (clientIp != null && ticketRateLimitConfigProperties.getIpWhitelist() != null
                    && ticketRateLimitConfigProperties.getIpWhitelist().contains(clientIp))
                    || (userId != null && ticketRateLimitConfigProperties.getUserWhitelist() != null
                    && ticketRateLimitConfigProperties.getUserWhitelist().contains(userId));
        } catch (Exception e) {
            return false;
        }
    }

    private void checkBans(Long sessionId, 
                           Long userId, 
                           String clientIp) {
        
        if (Objects.nonNull(clientIp)) {
            boolean ipBlocked = Boolean.TRUE.equals(redisCache.hasKey(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_BLOCK_IP_TAG_KEY, sessionId, clientIp)));
            if (ipBlocked) {
                throw new ShowGoFrameException(BaseCode.TICKET_RATE_LIMIT_IP_EXCEEDED);
            }
        }
        boolean userBlocked = Boolean.TRUE.equals(redisCache.hasKey(
                RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_BLOCK_USER_TAG_KEY, sessionId, userId)));
        if (userBlocked) {
            throw new ShowGoFrameException(BaseCode.TICKET_RATE_LIMIT_USER_EXCEEDED);
        }
    }

    private List<String> buildRateLimitKeys(Long sessionId, 
                                            Long userId, 
                                            String clientIp, 
                                            boolean useSliding) {
        List<String> keys = new ArrayList<>(2);
        if (Objects.nonNull(clientIp)) {
            String ipKey = useSliding
                    ? RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_LIMIT_IP_SW_TAG_KEY, sessionId, clientIp).getRelKey()
                    : RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_LIMIT_IP_TB_TAG_KEY, sessionId, clientIp).getRelKey();
            keys.add(ipKey);
        }
        String userKey = useSliding
                ? RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_LIMIT_USER_SW_TAG_KEY, sessionId, userId).getRelKey()
                : RedisKeyBuild.createRedisKey(RedisKeyManage.TICKET_LIMIT_USER_TB_TAG_KEY, sessionId, userId).getRelKey();
        keys.add(userKey);
        return keys;
    }

    private String[] buildArgs(int ipWindowMillis, 
                               int ipMaxAttempts, 
                               int userWindowMillis, 
                               int userMaxAttempts) {
        String[] args = new String[4];
        args[0] = String.valueOf(ipWindowMillis);
        args[1] = String.valueOf(ipMaxAttempts);
        args[2] = String.valueOf(userWindowMillis);
        args[3] = String.valueOf(userMaxAttempts);
        return args;
    }

    private RateLimitContext buildContext(Long sessionId, 
                                          Long userId, 
                                          String clientIp, 
                                          List<String> keys,
                                          boolean useSliding,
                                          int ipWindowMillis, int ipMaxAttempts,
                                          int userWindowMillis, int userMaxAttempts) {
        return new RateLimitContext(
                sessionId,
                userId,
                clientIp,
                keys,
                useSliding,
                ipWindowMillis,
                ipMaxAttempts,
                userWindowMillis,
                userMaxAttempts
        );
    }

    private void safeBeforeExecute(RateLimitContext ctx) {
        rateLimitEventListener.onBeforeExecute(ctx);
    }

    private Integer executeLua(boolean useSliding, List<String> keys, String[] args) {
        return useSliding 
                ?
                slidingRateLimitOperate.execute(keys, args).intValue() 
                :
                tokenBucketRateLimitOperate.execute(keys, args).intValue();
    }

    private void handleResult(RateLimitContext ctx) {
        Integer result = ctx.getResult();
        if (BaseCode.SUCCESS.getCode().equals(result)) {
            rateLimitEventListener.onAllowed(ctx);
            return;
        }
        if (BaseCode.TICKET_RATE_LIMIT_IP_EXCEEDED.getCode().equals(result)) {
            rateLimitEventListener.onBlocked(ctx, BaseCode.TICKET_RATE_LIMIT_IP_EXCEEDED);
            rateLimitPenaltyPolicy.apply(ctx, BaseCode.TICKET_RATE_LIMIT_IP_EXCEEDED);
            throw new ShowGoFrameException(BaseCode.TICKET_RATE_LIMIT_IP_EXCEEDED);
        }
        if (BaseCode.TICKET_RATE_LIMIT_USER_EXCEEDED.getCode().equals(result)) {
            rateLimitEventListener.onBlocked(ctx, BaseCode.TICKET_RATE_LIMIT_USER_EXCEEDED);
            rateLimitPenaltyPolicy.apply(ctx, BaseCode.TICKET_RATE_LIMIT_USER_EXCEEDED);
            throw new ShowGoFrameException(BaseCode.TICKET_RATE_LIMIT_USER_EXCEEDED);
        }
        throw new ShowGoFrameException("操作频繁，请稍后再试");
    }
}

package org.javaup.service.impl;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IWaitlistNotifyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 候补通知：示例项目走日志，接入短信/App Push 时替换此处即可。
 */
@Slf4j
@Service
public class WaitlistNotifyServiceImpl implements IWaitlistNotifyService {

    @Resource
    private RedisCache redisCache;

    @Value("${ticket.waitlist.notify-dedup-seconds:1800}")
    private long dedupSeconds;

    @Value("${ticket.waitlist.notify.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${ticket.waitlist.notify.sms.to:}")
    private String smsTo;

    @Value("${ticket.waitlist.notify.app.enabled:false}")
    private boolean appEnabled;

    @Override
    public void sendWaitlistNotify(Long sessionId, Long userId, List<String> seatCodes) {
        try {
            boolean shouldNotify = redisCache.setIfAbsent(
                    RedisKeyBuild.createRedisKey(
                            RedisKeyManage.SEAT_WAITLIST_NOTIFY_DEDUP_KEY, sessionId, userId),
                    "1", dedupSeconds, TimeUnit.SECONDS);
            if (!shouldNotify) {
                return;
            }
            String content = String.format("[候补成功] sessionId=%s userId=%s seats=%s",
                    sessionId, userId, StrUtil.join(",", seatCodes));
            if (smsEnabled && StrUtil.isNotBlank(smsTo)) {
                log.info("[WAITLIST_SMS] to={} content={}", smsTo, content);
            }
            if (appEnabled) {
                log.info("[WAITLIST_APP] userId={} content={}", userId, content);
            }
        } catch (Exception e) {
            log.warn("发送候补通知异常 sessionId={} userId={}", sessionId, userId, e);
        }
    }
}

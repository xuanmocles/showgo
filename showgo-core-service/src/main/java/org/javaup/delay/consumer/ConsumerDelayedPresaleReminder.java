package org.javaup.delay.consumer;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.ConsumerTask;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.delay.message.DelayedPresaleReminderMessage;
import org.javaup.entity.UserInfo;
import org.javaup.model.SessionDetail;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IShowSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.DELAY_PRESALE_REMINDER;

/**
 * 开售前提醒：从「等级人群 + 场馆高活跃买家」里挑人推送，并按用户去重控频。
 */
@Slf4j
@Component
public class ConsumerDelayedPresaleReminder implements ConsumerTask {

    @Resource
    private RedisCache redisCache;

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private IUserInfoService userInfoService;

    @Value("${ticket.reminder.notify.max-users:1000}")
    private int maxNotifyUsers;

    @Value("${ticket.reminder.notify.default-min-level:1}")
    private int defaultMinLevel;

    @Value("${ticket.reminder.notify.user-level-max:10}")
    private int maxUserLevel;

    @Value("${ticket.reminder.notify.top-buyers.enabled:true}")
    private boolean topBuyersEnabled;

    @Value("${ticket.reminder.notify.top-buyers.days:30}")
    private int topBuyersDays;

    @Value("${ticket.reminder.notify.top-buyers.count:200}")
    private int topBuyersCount;

    @Value("${ticket.reminder.notify.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${ticket.reminder.notify.sms.to:}")
    private String smsTo;

    @Value("${ticket.reminder.notify.app.enabled:false}")
    private boolean appEnabled;

    @Override
    public void execute(String content) {
        try {
            DelayedPresaleReminderMessage message =
                    JSON.parseObject(content, DelayedPresaleReminderMessage.class);
            if (message == null || message.getSessionId() == null) {
                return;
            }
            SessionDetail detail = showSessionService.queryDetail(message.getSessionId());
            Set<String> audience = buildAudience(detail);
            int notified = 0;
            for (String userId : audience) {
                if (notifyOne(message, userId)) {
                    notified++;
                }
            }
            log.info("[开售提醒] sessionId={} 人群={} 实际通知={}", message.getSessionId(), audience.size(), notified);
        } catch (Exception e) {
            log.warn("[开售提醒] 执行异常", e);
        }
    }

    private boolean notifyOne(DelayedPresaleReminderMessage message, String userId) {
        boolean shouldNotify = redisCache.setIfAbsent(
                RedisKeyBuild.createRedisKey(RedisKeyManage.PRESALE_REMINDER_DEDUP_KEY,
                        message.getSessionId(), userId),
                "1", 30, TimeUnit.MINUTES);
        if (!shouldNotify) {
            return false;
        }
        String text = String.format("[开售提醒] sessionId=%s 开售时间=%s",
                message.getSessionId(), message.getSaleBeginTime());
        if (smsEnabled && StrUtil.isNotBlank(smsTo)) {
            log.info("[PRESALE_SMS] to={} content={}", smsTo, text);
        }
        if (appEnabled) {
            log.info("[PRESALE_APP] userId={} content={}", userId, text);
        }
        return true;
    }

    private Set<String> buildAudience(SessionDetail detail) {
        Set<String> audience = new LinkedHashSet<>();
        List<Integer> levels = resolveLevels(detail);
        List<RedisKeyBuild> levelKeys = new ArrayList<>(levels.size());
        for (Integer level : levels) {
            levelKeys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LEVEL_MEMBERS_TAG_KEY, level));
        }
        if (CollectionUtil.isNotEmpty(levelKeys)) {
            RedisKeyBuild first = levelKeys.get(0);
            if (levelKeys.size() == 1) {
                audience.addAll(redisCache.distinctRandomMembersForSet(first, maxNotifyUsers, String.class));
            } else {
                RedisKeyBuild dest = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.USER_LEVEL_MEMBERS_UNION_TAG_KEY, "reminder");
                redisCache.unionAndStoreForSet(first, levelKeys.subList(1, levelKeys.size()), dest);
                redisCache.expire(dest, 60, TimeUnit.SECONDS);
                audience.addAll(redisCache.distinctRandomMembersForSet(dest, maxNotifyUsers, String.class));
            }
        }
        // 等级索引为空时退回数据库查询，保证提醒不会因为索引缺失而完全发不出去
        if (audience.isEmpty()) {
            List<UserInfo> userInfos = userInfoService.lambdaQuery()
                    .select(UserInfo::getUserId)
                    .ge(UserInfo::getLevel, defaultMinLevel)
                    .last("limit " + maxNotifyUsers)
                    .list();
            for (UserInfo userInfo : userInfos) {
                if (userInfo.getUserId() != null) {
                    audience.add(String.valueOf(userInfo.getUserId()));
                }
            }
        }
        if (topBuyersEnabled && detail.getVenueId() != null) {
            for (String userId : readTopBuyers(detail.getVenueId())) {
                audience.add(userId);
            }
        }
        return audience;
    }

    private List<Integer> resolveLevels(SessionDetail detail) {
        List<Integer> levels = new ArrayList<>();
        if (StrUtil.isNotBlank(detail.getAllowedLevels())) {
            for (String part : detail.getAllowedLevels().split(",")) {
                if (StrUtil.isNotBlank(part)) {
                    levels.add(Integer.valueOf(part.trim()));
                }
            }
            return levels;
        }
        int min = detail.getMinLevel() == null ? defaultMinLevel : detail.getMinLevel();
        for (int level = Math.max(min, 1); level <= Math.max(maxUserLevel, min); level++) {
            levels.add(level);
        }
        return levels;
    }

    private Collection<String> readTopBuyers(Long venueId) {
        try {
            LocalDate today = LocalDate.now();
            DateTimeFormatter formatter = DateTimeFormatter.BASIC_ISO_DATE;
            List<RedisKeyBuild> dailyKeys = new ArrayList<>();
            for (int i = 0; i < Math.max(topBuyersDays, 1); i++) {
                dailyKeys.add(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.VENUE_TOP_BUYERS_DAILY_TAG_KEY,
                        venueId, today.minusDays(i).format(formatter)));
            }
            if (dailyKeys.size() == 1) {
                return redisCache.getReverseRangeForSortedSet(dailyKeys.get(0), 0,
                        Math.max(topBuyersCount - 1, 0), String.class);
            }
            RedisKeyBuild dest = RedisKeyBuild.createRedisKey(
                    RedisKeyManage.VENUE_TOP_BUYERS_UNION_TAG_KEY, venueId, "reminder");
            redisCache.unionAndStoreForSortedSet(dailyKeys.get(0),
                    dailyKeys.subList(1, dailyKeys.size()), dest);
            redisCache.expire(dest, 60, TimeUnit.SECONDS);
            return redisCache.getReverseRangeForSortedSet(dest, 0,
                    Math.max(topBuyersCount - 1, 0), String.class);
        } catch (Exception e) {
            log.warn("读取场馆高活跃买家失败 venueId={}", venueId, e);
            return List.of();
        }
    }

    @Override
    public String topic() {
        return SpringUtil.getPrefixDistinctionName() + "-" + DELAY_PRESALE_REMINDER;
    }
}

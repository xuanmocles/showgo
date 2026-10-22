package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.context.DelayQueueContext;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.delay.message.DelayedSeatReleaseMessage;
import org.javaup.dto.WaitlistDto;
import org.javaup.entity.UserInfo;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.WaitlistStatus;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.kafka.message.SeatChangeMessage;
import org.javaup.kafka.producer.SeatChangeProducer;
import org.javaup.lua.SeatLockOperate;
import org.javaup.model.SessionDetail;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeatWaitlistService;
import org.javaup.service.IShowSessionService;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IWaitlistNotifyService;
import org.javaup.utils.UserHolder;
import org.javaup.vo.WaitlistStatusVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.javaup.constant.Constant.DELAY_SEAT_LOCK_RELEASE;
import static org.javaup.constant.Constant.SEAT_CHANGE_TOPIC;

/**
 * 候补排队：ZSet 以候补时间为 score 保证先到先得，座位释放后自动为最早候补用户锁定座位。
 */
@Slf4j
@Service
public class SeatWaitlistServiceImpl implements ISeatWaitlistService {

    @Resource
    private RedisCache redisCache;

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private IWaitlistNotifyService waitlistNotifyService;

    @Resource
    private SeatLockOperate seatLockOperate;

    @Resource
    private SeatChangeProducer seatChangeProducer;

    @Resource
    private DelayQueueContext delayQueueContext;

    @Value("${ticket.seat.lock-seconds:900}")
    private long lockSeconds;

    @Value("${ticket.seat.max-seat-per-user:6}")
    private int maxSeatPerUser;

    @Override
    public WaitlistStatusVo joinWaitlist(WaitlistDto waitlistDto) {
        Long userId = UserHolder.getUser().getId();
        Long sessionId = waitlistDto.getSessionId();
        // 校验场次存在，避免为不存在的场次建立候补队列
        showSessionService.queryDetail(sessionId);
        int expectCount = waitlistDto.getExpectCount() == null ? 1 : waitlistDto.getExpectCount();

        RedisKeyBuild zsetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_WAITLIST_ZSET_TAG_KEY, sessionId);
        redisCache.addForSortedSet(zsetKey, String.valueOf(userId),
                (double) System.currentTimeMillis(), 7, TimeUnit.DAYS);
        redisCache.putHash(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_WAITLIST_STATUS_TAG_KEY, sessionId),
                String.valueOf(userId),
                WaitlistStatus.WAITING.getCode() + ":" + expectCount,
                7, TimeUnit.DAYS);
        return getStatus(sessionId);
    }

    @Override
    public WaitlistStatusVo cancelWaitlist(Long sessionId) {
        removeFromWaitlist(sessionId, UserHolder.getUser().getId());
        return getStatus(sessionId);
    }

    @Override
    public WaitlistStatusVo getStatus(Long sessionId) {
        Long userId = UserHolder.getUser().getId();
        return statusOf(sessionId, userId);
    }

    @Override
    public List<WaitlistStatusVo> getStatusBatch(List<Long> sessionIds) {
        Long userId = UserHolder.getUser().getId();
        return sessionIds.stream().map(id -> statusOf(id, userId)).collect(Collectors.toList());
    }

    @Override
    public void removeFromWaitlist(Long sessionId, Long userId) {
        redisCache.delForSortedSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_WAITLIST_ZSET_TAG_KEY, sessionId),
                String.valueOf(userId));
        redisCache.delForHash(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_WAITLIST_STATUS_TAG_KEY, sessionId),
                String.valueOf(userId));
    }

    @Override
    public boolean autoIssueToEarliestCandidate(Long sessionId, List<Integer> freeIndexes, BusinessType businessType) {
        if (CollectionUtil.isEmpty(freeIndexes)) {
            return false;
        }
        SessionDetail detail = showSessionService.queryDetail(sessionId);
        boolean anyIssued = false;
        for (Integer index : freeIndexes) {
            Long candidateUserId = findEarliestCandidate(sessionId, detail);
            if (Objects.isNull(candidateUserId)) {
                break;
            }
            if (issueSeat(sessionId, candidateUserId, index)) {
                anyIssued = true;
            } else {
                // 该候补用户不满足条件或超出限购，移出队列避免阻塞后面的用户
                removeFromWaitlist(sessionId, candidateUserId);
            }
        }
        return anyIssued;
    }

    private boolean issueSeat(Long sessionId, Long candidateUserId, Integer index) {
        String lockToken = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        List<String> keys = List.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_MAP_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_LOCK_HASH_TAG_KEY, sessionId).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_LOCK_TOKEN_TAG_KEY,
                        sessionId, lockToken).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_USER_TAG_KEY, sessionId).getRelKey());
        String[] args = new String[]{
                String.valueOf(candidateUserId),
                lockToken,
                String.valueOf(lockSeconds),
                "1",
                String.valueOf(maxSeatPerUser),
                String.valueOf(index)};

        Integer code = seatLockOperate.execute(keys, args);
        if (code == null || !code.equals(BaseCode.SUCCESS.getCode())) {
            log.info("候补补位锁座失败 sessionId={} userId={} index={} code={}",
                    sessionId, candidateUserId, index, code);
            return false;
        }
        List<String> seatCodes = showSessionService.seatCodesOf(sessionId, List.of(index));
        removeFromWaitlist(sessionId, candidateUserId);
        redisCache.putHash(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_WAITLIST_STATUS_TAG_KEY, sessionId),
                String.valueOf(candidateUserId),
                WaitlistStatus.SUCCESS.getCode() + ":1",
                7, TimeUnit.DAYS);
        waitlistNotifyService.sendWaitlistNotify(sessionId, candidateUserId, seatCodes);
        try {
            seatChangeProducer.sendPayload(
                    SpringUtil.getPrefixDistinctionName() + "-" + SEAT_CHANGE_TOPIC,
                    new SeatChangeMessage(sessionId, List.of(index), 1, "WAITLIST_LOCK"));
        } catch (Exception e) {
            log.warn("广播候补锁座失败", e);
        }
        // 候补锁座同样有支付时限，超时未下单自动释放
        delayQueueContext.sendMessage(
                SpringUtil.getPrefixDistinctionName() + "-" + DELAY_SEAT_LOCK_RELEASE,
                JSON.toJSONString(new DelayedSeatReleaseMessage("LOCK", sessionId, candidateUserId, lockToken, null)),
                lockSeconds, TimeUnit.SECONDS);
        return true;
    }

    /** 按候补时间从早到晚找第一个仍满足人群规则的用户 */
    private Long findEarliestCandidate(Long sessionId, SessionDetail detail) {
        RedisKeyBuild zsetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_WAITLIST_ZSET_TAG_KEY, sessionId);
        long offset = 0L;
        while (offset < 50) {
            Set<ZSetOperations.TypedTuple<String>> page = redisCache.rangeByScoreWithScoreForSortedSet(
                    zsetKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, offset, 1, String.class);
            if (CollectionUtil.isEmpty(page)) {
                return null;
            }
            ZSetOperations.TypedTuple<String> tuple = page.iterator().next();
            offset++;
            if (Objects.isNull(tuple) || StrUtil.isBlank(tuple.getValue())) {
                continue;
            }
            Long candidate = Long.valueOf(tuple.getValue());
            if (matchUserLevel(detail, candidate)) {
                return candidate;
            }
            log.info("候补用户不满足人群规则，跳过 sessionId={} userId={}", sessionId, candidate);
        }
        return null;
    }

    private boolean matchUserLevel(SessionDetail detail, Long userId) {
        if (StrUtil.isBlank(detail.getAllowedLevels()) && Objects.isNull(detail.getMinLevel())) {
            return true;
        }
        UserInfo userInfo = userInfoService.getByUserId(userId);
        if (Objects.isNull(userInfo) || Objects.isNull(userInfo.getLevel())) {
            return false;
        }
        if (StrUtil.isNotBlank(detail.getAllowedLevels())) {
            boolean contains = Arrays.stream(detail.getAllowedLevels().split(","))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .map(Integer::valueOf)
                    .anyMatch(level -> level.equals(userInfo.getLevel()));
            if (!contains) {
                return false;
            }
        }
        return Objects.isNull(detail.getMinLevel()) || userInfo.getLevel() >= detail.getMinLevel();
    }

    private WaitlistStatusVo statusOf(Long sessionId, Long userId) {
        WaitlistStatusVo vo = new WaitlistStatusVo();
        vo.setSessionId(sessionId);
        String value = redisCache.getForHash(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_WAITLIST_STATUS_TAG_KEY, sessionId),
                String.valueOf(userId), String.class);
        Integer status = WaitlistStatus.NOT_IN_QUEUE.getCode();
        Integer expectCount = 1;
        if (StrUtil.isNotBlank(value)) {
            String[] parts = value.split(":");
            status = Integer.valueOf(parts[0]);
            if (parts.length > 1) {
                expectCount = Integer.valueOf(parts[1]);
            }
        }
        vo.setStatus(status);
        vo.setExpectCount(expectCount);
        Long rank = redisCache.rankForSortedSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_WAITLIST_ZSET_TAG_KEY, sessionId),
                String.valueOf(userId));
        vo.setAheadCount(rank == null ? 0L : rank);
        return vo;
    }
}

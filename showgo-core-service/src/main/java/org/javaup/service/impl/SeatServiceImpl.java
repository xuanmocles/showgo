package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.context.DelayQueueContext;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.delay.message.DelayedSeatReleaseMessage;
import org.javaup.dto.SeatLockDto;
import org.javaup.dto.SeatRecommendDto;
import org.javaup.entity.SeatArea;
import org.javaup.entity.SeatLockLog;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.SeatLockStatus;
import org.javaup.enums.SeatQuotaOperate;
import org.javaup.enums.SeatStatus;
import org.javaup.enums.SessionStatus;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.kafka.message.SeatChangeMessage;
import org.javaup.kafka.producer.SeatChangeProducer;
import org.javaup.kafka.redis.SeatRedisData;
import org.javaup.lua.SeatLockOperate;
import org.javaup.lua.SeatMapReadOperate;
import org.javaup.lua.SeatRecommendOperate;
import org.javaup.mapper.SeatLockLogMapper;
import org.javaup.model.SessionDetail;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeatService;
import org.javaup.service.ISeatWaitlistService;
import org.javaup.service.IShowSessionService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.UserHolder;
import org.javaup.vo.SeatAreaVo;
import org.javaup.vo.SeatLockVo;
import org.javaup.vo.SeatMapVo;
import org.javaup.vo.SeatRecommendVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.javaup.constant.Constant.DELAY_SEAT_LOCK_RELEASE;
import static org.javaup.constant.Constant.SEAT_CHANGE_TOPIC;

/**
 * 座位：BitField 座位图的读取、连座推荐、锁定与释放。
 */
@Slf4j
@Service
public class SeatServiceImpl implements ISeatService {

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private ISeatWaitlistService seatWaitlistService;

    @Resource
    private SeatMapReadOperate seatMapReadOperate;

    @Resource
    private SeatRecommendOperate seatRecommendOperate;

    @Resource
    private SeatLockOperate seatLockOperate;

    @Resource
    private SeatRedisData seatRedisData;

    @Resource
    private SeatLockLogMapper seatLockLogMapper;

    @Resource
    private SeatChangeProducer seatChangeProducer;

    @Resource
    private RedisCache redisCache;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private DelayQueueContext delayQueueContext;

    @Value("${ticket.seat.lock-seconds:900}")
    private long lockSeconds;

    @Value("${ticket.seat.max-seat-per-user:6}")
    private int maxSeatPerUser;

    @Value("${ticket.seat.max-seat-per-order:6}")
    private int maxSeatPerOrder;

    @Override
    public SeatMapVo querySeatMap(Long sessionId) {
        SessionDetail detail = showSessionService.queryDetail(sessionId);
        showSessionService.warmUpSeatMap(sessionId);
        long ttlSeconds = showSessionService.resolveTtlSeconds(sessionId);

        RedisKeyBuild mapKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_MAP_TAG_KEY, sessionId);
        String statuses = seatMapReadOperate.execute(mapKey.getRelKey(), detail.getSeatTotal());

        SeatMapVo vo = new SeatMapVo();
        vo.setSessionId(detail.getSessionId());
        vo.setShowId(detail.getShowId());
        vo.setShowName(detail.getShowName());
        vo.setVenueName(detail.getVenueName());
        vo.setAddress(detail.getAddress());
        vo.setSessionName(detail.getSessionName());
        vo.setBeginTime(detail.getBeginTime());
        vo.setSaleEndTime(detail.getSaleEndTime());
        vo.setSeatTotal(detail.getSeatTotal());
        vo.setSeatAvailable(redisSeatAvailable(sessionId, detail));

        List<SeatAreaVo> areaVos = new ArrayList<>();
        for (SeatArea area : detail.getAreas()) {
            SeatAreaVo areaVo = new SeatAreaVo();
            areaVo.setAreaId(area.getId());
            areaVo.setAreaCode(area.getAreaCode());
            areaVo.setAreaName(area.getAreaName());
            areaVo.setAreaType(area.getAreaType());
            areaVo.setPrice(area.getPrice());
            areaVo.setColor(area.getColor());
            areaVo.setRowCount(area.getRowCount());
            areaVo.setSeatPerRow(area.getSeatPerRow());
            areaVo.setStartIndex(area.getStartIndex());
            areaVo.setSeats(sliceSeats(statuses, area));
            areaVos.add(areaVo);
        }
        vo.setAreas(areaVos);
        // 读取座位图时顺带续期，热场次的位图不会被提前淘汰
        redisCache.expire(mapKey, ttlSeconds, TimeUnit.SECONDS);
        return vo;
    }

    @Override
    public SeatRecommendVo recommend(SeatRecommendDto recommendDto) {
        SessionDetail detail = showSessionService.queryDetail(recommendDto.getSessionId());
        showSessionService.warmUpSeatMap(recommendDto.getSessionId());
        int need = recommendDto.getCount() == null ? 2 : recommendDto.getCount();
        RedisKeyBuild mapKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.SEAT_MAP_TAG_KEY, recommendDto.getSessionId());

        List<SeatArea> candidates;
        if (Objects.nonNull(recommendDto.getAreaId())) {
            candidates = detail.getAreas().stream()
                    .filter(area -> Objects.equals(area.getId(), recommendDto.getAreaId()))
                    .collect(Collectors.toList());
        } else {
            // 未指定分区时优先推荐价格档位更高的内场区域
            candidates = detail.getAreas().stream()
                    .sorted(Comparator.comparing(SeatArea::getPrice).reversed())
                    .collect(Collectors.toList());
        }

        for (SeatArea area : candidates) {
            String found = seatRecommendOperate.execute(
                    mapKey.getRelKey(),
                    area.getStartIndex(),
                    area.getStartIndex() + area.getSeatCount() - 1,
                    need);
            if (StrUtil.isNotBlank(found)) {
                List<Integer> indexes = parseIndexes(found);
                SeatRecommendVo vo = new SeatRecommendVo();
                vo.setAreaId(area.getId());
                vo.setAreaCode(area.getAreaCode());
                vo.setAreaName(area.getAreaName());
                vo.setPrice(area.getPrice());
                vo.setSeatIndexes(indexes);
                vo.setSeatCodes(showSessionService.seatCodesOf(detail.getSessionId(), indexes));
                return vo;
            }
        }
        throw new ShowGoFrameException(BaseCode.NO_CONTINUOUS_SEAT);
    }

    @Override
    public SeatLockVo lock(SeatLockDto seatLockDto) {
        Long userId = UserHolder.getUser().getId();
        SessionDetail detail = showSessionService.queryDetail(seatLockDto.getSessionId());
        validateSaleWindow(detail);
        showSessionService.warmUpSeatMap(seatLockDto.getSessionId());

        List<Integer> indexes = seatLockDto.getSeatIndexes().stream()
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        if (indexes.size() > maxSeatPerOrder) {
            throw new ShowGoFrameException(BaseCode.SEAT_LIMIT_EXCEEDED);
        }
        SeatArea area = resolveArea(detail, indexes);

        String lockToken = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        List<String> keys = List.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_MAP_TAG_KEY, detail.getSessionId()).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_LOCK_HASH_TAG_KEY, detail.getSessionId()).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_LOCK_TOKEN_TAG_KEY,
                        detail.getSessionId(), lockToken).getRelKey(),
                RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_USER_TAG_KEY, detail.getSessionId()).getRelKey());

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(userId));
        args.add(lockToken);
        args.add(String.valueOf(lockSeconds));
        args.add(String.valueOf(indexes.size()));
        args.add(String.valueOf(maxSeatPerUser));
        indexes.forEach(index -> args.add(String.valueOf(index)));

        Integer code = seatLockOperate.execute(keys, args.toArray(new String[0]));
        if (code == null || !code.equals(BaseCode.SUCCESS.getCode())) {
            BaseCode baseCode = BaseCode.getRc(code == null ? -1 : code);
            throw new ShowGoFrameException(baseCode == null ? "锁座失败，请重试" : baseCode.getMsg());
        }

        // Redis 座位已经锁定，后续步骤（落库、广播、登记延迟释放）任何一步失败都必须把座位还回去，
        // 否则会留下「Redis 已锁、没有任何记录」的孤儿座位，直到锁座有效期结束都无法被他人选中。
        List<String> seatCodes = showSessionService.seatCodesOf(detail.getSessionId(), indexes);
        long traceId = snowflakeIdGenerator.nextId();
        long amount = (area.getPrice() == null ? 0L : area.getPrice()) * indexes.size();
        LocalDateTime expireTime = LocalDateTimeUtil.offset(LocalDateTimeUtil.now(), lockSeconds, ChronoUnit.SECONDS);
        try {
            seatLockLogMapper.insert(new SeatLockLog()
                    .setId(snowflakeIdGenerator.nextId())
                    .setSessionId(detail.getSessionId())
                    .setUserId(userId)
                    .setSeatCodes(StrUtil.join(",", seatCodes))
                    .setLockToken(lockToken)
                    .setTraceId(traceId)
                    .setStatus(SeatLockStatus.LOCKED.getCode())
                    .setExpireTime(expireTime)
                    .setCreateTime(LocalDateTime.now())
                    .setUpdateTime(LocalDateTime.now()));

            broadcast(detail.getSessionId(), indexes, SeatStatus.LOCKED.getCode(), "LOCK");
            scheduleLockRelease(detail.getSessionId(), userId, lockToken);
        } catch (Exception e) {
            log.error("锁座后续处理失败，回滚刚锁定的座位 sessionId={} userId={} seats={}",
                    detail.getSessionId(), userId, seatCodes, e);
            rollbackLockedSeats(detail.getSessionId(), userId, indexes, StrUtil.join(",", seatCodes));
            throw e;
        }

        SeatLockVo vo = new SeatLockVo();
        vo.setSessionId(detail.getSessionId());
        vo.setLockToken(lockToken);
        vo.setSeatCodes(seatCodes);
        vo.setAmount(amount);
        vo.setExpireTime(expireTime);
        return vo;
    }

    @Override
    public List<String> seatCodesOf(Long sessionId, List<Integer> seatIndexes) {
        return showSessionService.seatCodesOf(sessionId, seatIndexes);
    }

    @Override
    public List<Integer> seatIndexesOf(Long sessionId, List<String> seatCodes) {
        return showSessionService.seatIndexesOf(sessionId, seatCodes);
    }

    @Override
    public void release(Long sessionId, Long userId, List<Integer> seatIndexes, Long orderId,
                        BusinessType businessType) {
        if (CollectionUtil.isEmpty(seatIndexes)) {
            return;
        }
        List<String> seatCodes = showSessionService.seatCodesOf(sessionId, seatIndexes);
        RedisKeyBuild stockKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_STOCK_TAG_KEY, sessionId);
        String stockValue = redisCache.get(stockKey, String.class);
        int beforeQty = StrUtil.isBlank(stockValue) ? 0 : Integer.parseInt(stockValue);
        long traceId = snowflakeIdGenerator.nextId();
        seatRedisData.rollbackSeats(
                SeatQuotaOperate.YES,
                traceId,
                sessionId,
                userId,
                orderId,
                StrUtil.join(",", seatCodes),
                seatIndexes,
                beforeQty,
                seatIndexes.size(),
                beforeQty + seatIndexes.size(),
                showSessionService.resolveTtlSeconds(sessionId));
        broadcast(sessionId, seatIndexes, SeatStatus.AVAILABLE.getCode(), "RELEASE");
        // 释放出的座位按候补顺序自动补位
        try {
            seatWaitlistService.autoIssueToEarliestCandidate(sessionId, seatIndexes, businessType);
        } catch (Exception e) {
            log.warn("候补自动补位失败 sessionId={}", sessionId, e);
        }
    }

    private void validateSaleWindow(SessionDetail detail) {
        LocalDateTime now = LocalDateTimeUtil.now();
        Integer status = detail.getStatus();
        if (Objects.equals(status, SessionStatus.CANCELED.getCode())) {
            throw new ShowGoFrameException(BaseCode.SESSION_CANCELED);
        }
        if (Objects.equals(status, SessionStatus.FINISHED.getCode())) {
            throw new ShowGoFrameException(BaseCode.SESSION_ALREADY_BEGUN);
        }
        if (Objects.nonNull(detail.getSaleBeginTime()) && now.isBefore(detail.getSaleBeginTime())) {
            throw new ShowGoFrameException(BaseCode.SESSION_NOT_ON_SALE);
        }
        if (Objects.nonNull(detail.getSaleEndTime()) && now.isAfter(detail.getSaleEndTime())) {
            throw new ShowGoFrameException(BaseCode.SESSION_SALE_ENDED);
        }
    }

    /** 校验所选座位落在同一分区同一排且连续，并返回所在分区 */
    private SeatArea resolveArea(SessionDetail detail, List<Integer> indexes) {
        SeatArea matched = null;
        for (SeatArea area : detail.getAreas()) {
            boolean inArea = indexes.get(0) >= area.getStartIndex()
                    && indexes.get(indexes.size() - 1) < area.getStartIndex() + area.getSeatCount();
            if (inArea) {
                matched = area;
                break;
            }
        }
        if (Objects.isNull(matched)) {
            throw new ShowGoFrameException(BaseCode.SEAT_NOT_EXIST);
        }
        int firstRow = (indexes.get(0) - matched.getStartIndex()) / matched.getSeatPerRow();
        int lastRow = (indexes.get(indexes.size() - 1) - matched.getStartIndex()) / matched.getSeatPerRow();
        if (firstRow != lastRow) {
            throw new ShowGoFrameException(BaseCode.SEAT_AREA_MISMATCH);
        }
        for (int i = 1; i < indexes.size(); i++) {
            if (indexes.get(i) != indexes.get(i - 1) + 1) {
                throw new ShowGoFrameException(BaseCode.SEAT_NOT_CONTINUOUS);
            }
        }
        return matched;
    }

    private List<List<Integer>> sliceSeats(String statuses, SeatArea area) {
        List<List<Integer>> rows = new ArrayList<>(area.getRowCount());
        for (int row = 0; row < area.getRowCount(); row++) {
            List<Integer> seats = new ArrayList<>(area.getSeatPerRow());
            for (int col = 0; col < area.getSeatPerRow(); col++) {
                int index = area.getStartIndex() + row * area.getSeatPerRow() + col;
                if (index < statuses.length()) {
                    seats.add(Character.getNumericValue(statuses.charAt(index)));
                } else {
                    seats.add(SeatStatus.AVAILABLE.getCode());
                }
            }
            rows.add(seats);
        }
        return rows;
    }

    private Integer redisSeatAvailable(Long sessionId, SessionDetail detail) {
        String value = redisCache.get(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_STOCK_TAG_KEY, sessionId), String.class);
        if (StrUtil.isBlank(value)) {
            return detail.getSeatTotal();
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return detail.getSeatTotal();
        }
    }

    private List<Integer> parseIndexes(String csv) {
        List<Integer> indexes = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (StrUtil.isNotBlank(part)) {
                indexes.add(Integer.valueOf(part.trim()));
            }
        }
        return indexes;
    }

    private void broadcast(Long sessionId, List<Integer> indexes, Integer status, String changeType) {
        try {
            seatChangeProducer.sendPayload(
                    SpringUtil.getPrefixDistinctionName() + "-" + SEAT_CHANGE_TOPIC,
                    new SeatChangeMessage(sessionId, indexes, status, changeType));
        } catch (Exception e) {
            log.warn("广播座位变更失败 sessionId={}", sessionId, e);
        }
    }

    /** 锁座后置步骤失败时的补偿：把刚锁定的座位恢复为可售，并回收用户额度 */
    private void rollbackLockedSeats(Long sessionId, Long userId, List<Integer> indexes, String seatCodes) {
        try {
            RedisKeyBuild stockKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_STOCK_TAG_KEY, sessionId);
            String stockValue = redisCache.get(stockKey, String.class);
            int beforeQty = StrUtil.isBlank(stockValue) ? 0 : Integer.parseInt(stockValue);
            seatRedisData.rollbackSeats(
                    SeatQuotaOperate.YES,
                    snowflakeIdGenerator.nextId(),
                    sessionId,
                    userId,
                    null,
                    seatCodes,
                    indexes,
                    beforeQty,
                    indexes.size(),
                    beforeQty + indexes.size(),
                    showSessionService.resolveTtlSeconds(sessionId));
            broadcast(sessionId, indexes, SeatStatus.AVAILABLE.getCode(), "RELEASE");
        } catch (Exception ex) {
            log.error("锁座补偿回滚失败，等待对账任务收敛 sessionId={} seats={}", sessionId, seatCodes, ex);
        }
    }

    /** 登记锁座超时释放：到点后由延迟队列消费者检查锁是否仍未被下单消耗 */
    private void scheduleLockRelease(Long sessionId, Long userId, String lockToken) {
        DelayedSeatReleaseMessage message = new DelayedSeatReleaseMessage(
                "LOCK", sessionId, userId, lockToken, null);
        delayQueueContext.sendMessage(
                SpringUtil.getPrefixDistinctionName() + "-" + DELAY_SEAT_LOCK_RELEASE,
                JSON.toJSONString(message),
                lockSeconds,
                TimeUnit.SECONDS);
    }
}

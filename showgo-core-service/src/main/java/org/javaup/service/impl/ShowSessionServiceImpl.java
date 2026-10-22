package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.cache.SessionLocalCache;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.SeatArea;
import org.javaup.entity.Show;
import org.javaup.entity.ShowSeat;
import org.javaup.entity.ShowSession;
import org.javaup.entity.Venue;
import org.javaup.enums.SeatStatus;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.lua.SeatMapInitOperate;
import org.javaup.mapper.SeatAreaMapper;
import org.javaup.mapper.ShowSeatMapper;
import org.javaup.mapper.ShowSessionMapper;
import org.javaup.model.SessionDetail;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IShowService;
import org.javaup.service.IShowSessionService;
import org.javaup.service.IVenueService;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.javaup.util.ServiceLockTool;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_SESSION;
import static org.javaup.constant.DistributedLockConstants.UPDATE_SESSION_SEAT_LOCK;
import static org.javaup.utils.RedisConstants.CACHE_NULL_TTL;
import static org.javaup.utils.RedisConstants.LOCK_SESSION_SEAT_KEY;

/**
 * 场次：负责聚合详情缓存、座位图预热与重建、座位索引换算。
 */
@Slf4j
@Service
public class ShowSessionServiceImpl extends ServiceImpl<ShowSessionMapper, ShowSession>
        implements IShowSessionService {

    @Resource
    private RedisCache redisCache;

    @Resource
    private SessionLocalCache sessionLocalCache;

    @Resource
    private SeatAreaMapper seatAreaMapper;

    @Resource
    private ShowSeatMapper showSeatMapper;

    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;

    @Resource
    private IShowService showService;

    @Resource
    private IVenueService venueService;

    @Resource
    private ServiceLockTool serviceLockTool;

    @Resource
    private SeatMapInitOperate seatMapInitOperate;

    @Override
    public SessionDetail queryDetail(Long sessionId) {
        RedisKeyBuild cacheKey = RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SESSION_KEY, sessionId);
        RedisKeyBuild nullKey = RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SESSION_NULL_KEY, sessionId);

        SessionDetail local = sessionLocalCache.get(cacheKey.getRelKey());
        if (Objects.nonNull(local)) {
            return local;
        }
        SessionDetail cached = redisCache.get(cacheKey, SessionDetail.class);
        if (Objects.nonNull(cached)) {
            sessionLocalCache.put(cacheKey.getRelKey(), cached);
            return cached;
        }
        if (!bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SESSION)
                .contains(String.valueOf(sessionId))) {
            throw new ShowGoFrameException(org.javaup.enums.BaseCode.SESSION_NOT_EXIST);
        }
        if (Boolean.TRUE.equals(redisCache.hasKey(nullKey))) {
            throw new ShowGoFrameException(org.javaup.enums.BaseCode.SESSION_NOT_EXIST);
        }

        RLock lock = serviceLockTool.getLock(LockType.Reentrant, LOCK_SESSION_SEAT_KEY,
                new String[]{String.valueOf(sessionId)});
        lock.lock();
        try {
            // 双重检查：拿到锁后可能已被其他线程重建
            cached = redisCache.get(cacheKey, SessionDetail.class);
            if (Objects.nonNull(cached)) {
                sessionLocalCache.put(cacheKey.getRelKey(), cached);
                return cached;
            }
            ShowSession session = getById(sessionId);
            if (Objects.isNull(session)) {
                redisCache.set(nullKey, "null", CACHE_NULL_TTL, TimeUnit.MINUTES);
                throw new ShowGoFrameException(org.javaup.enums.BaseCode.SESSION_NOT_EXIST);
            }
            SessionDetail detail = assemble(session);
            long ttlSeconds = resolveTtlSeconds(sessionId);
            redisCache.set(cacheKey, detail, ttlSeconds, TimeUnit.SECONDS);
            sessionLocalCache.put(cacheKey.getRelKey(), detail);
            return detail;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ShowSession> listByShow(Long showId) {
        return lambdaQuery()
                .eq(ShowSession::getShowId, showId)
                .in(ShowSession::getStatus, 1, 2, 3)
                .orderByAsc(ShowSession::getBeginTime)
                .list();
    }

    @Override
    public void warmUpSeatMap(Long sessionId) {
        SessionDetail detail = queryDetail(sessionId);
        long ttlSeconds = resolveTtlSeconds(sessionId);

        RedisKeyBuild stockKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_STOCK_TAG_KEY, sessionId);
        if (!Boolean.TRUE.equals(redisCache.hasKey(stockKey))) {
            ShowSession session = getById(sessionId);
            int available = Objects.isNull(session) ? detail.getSeatTotal() : session.getSeatAvailable();
            redisCache.set(stockKey, String.valueOf(available), ttlSeconds, TimeUnit.SECONDS);
        }

        RedisKeyBuild mapKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_MAP_TAG_KEY, sessionId);
        if (!Boolean.TRUE.equals(redisCache.hasKey(mapKey))) {
            rebuildSeatMap(sessionId);
        }

        // 索引 -> 座位编码，下单时按索引反查座位号，避免每次全表扫描
        RedisKeyBuild indexCodeKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_INDEX_CODE_TAG_KEY, sessionId);
        if (!Boolean.TRUE.equals(redisCache.hasKey(indexCodeKey))) {
            List<SeatArea> areas = detail.getAreas();
            Map<String, String> indexToCode = new HashMap<>(2048);
            for (ShowSeat seat : seatsOf(sessionId)) {
                Integer index = locateIndex(areas, seat);
                if (Objects.nonNull(index)) {
                    indexToCode.put(String.valueOf(index), seat.getSeatCode());
                }
            }
            if (CollectionUtil.isNotEmpty(indexToCode)) {
                redisCache.putHash(indexCodeKey, indexToCode, ttlSeconds, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void rebuildSeatMap(Long sessionId) {
        SessionDetail detail = queryDetail(sessionId);
        long ttlSeconds = resolveTtlSeconds(sessionId);
        List<ShowSeat> seats = seatsOf(sessionId);
        if (CollectionUtil.isEmpty(seats)) {
            return;
        }
        RedisKeyBuild mapKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_MAP_TAG_KEY, sessionId);
        // 注意这里手工加写锁，而不是用 @ServiceLock：写锁内部还要读场次详情，避免读写锁嵌套
        RLock writeLock = serviceLockTool.getLock(LockType.Write, UPDATE_SESSION_SEAT_LOCK,
                new String[]{String.valueOf(sessionId)});
        writeLock.lock();
        try {
            List<ShowSeat> abnormal = seats.stream()
                    .filter(seat -> Objects.nonNull(seat.getStatus())
                            && !SeatStatus.AVAILABLE.getCode().equals(seat.getStatus()))
                    .collect(Collectors.toList());
            List<String> pairs = new ArrayList<>(abnormal.size());
            for (ShowSeat seat : abnormal) {
                Integer index = locateIndex(detail.getAreas(), seat);
                if (Objects.nonNull(index)) {
                    pairs.add(index + ":" + seat.getStatus());
                }
            }
            seatMapInitOperate.execute(mapKey.getRelKey(), detail.getSeatTotal(), pairs);
            redisCache.expire(mapKey, ttlSeconds, TimeUnit.SECONDS);
            log.info("重建场次座位图完成 sessionId={} 座位数={} 非可售位={}",
                    sessionId, detail.getSeatTotal(), pairs.size());
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<SeatArea> listAreasWithOffset(Long sessionId) {
        List<SeatArea> areas = seatAreaMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeatArea>()
                        .eq(SeatArea::getSessionId, sessionId));
        areas.sort(Comparator.comparing(SeatArea::getAreaNo));
        int offset = 0;
        for (SeatArea area : areas) {
            int seatCount = Math.max(area.getRowCount(), 0) * Math.max(area.getSeatPerRow(), 0);
            area.setStartIndex(offset);
            area.setSeatCount(seatCount);
            offset += seatCount;
        }
        return areas;
    }

    @Override
    public List<String> seatCodesOf(Long sessionId, List<Integer> seatIndexes) {
        if (CollectionUtil.isEmpty(seatIndexes)) {
            return new ArrayList<>();
        }
        RedisKeyBuild indexCodeKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SEAT_INDEX_CODE_TAG_KEY, sessionId);
        List<String> hashKeys = seatIndexes.stream().map(String::valueOf).collect(Collectors.toList());
        List<String> codes = redisCache.multiGetForHash(indexCodeKey, hashKeys, String.class);
        if (CollectionUtil.isEmpty(codes)
                || codes.size() != seatIndexes.size()
                || codes.stream().anyMatch(Objects::isNull)) {
            // 缓存缺失时回源数据库
            warmUpSeatMap(sessionId);
            codes = redisCache.multiGetForHash(indexCodeKey, hashKeys, String.class);
        }
        return codes;
    }

    @Override
    public List<Integer> seatIndexesOf(Long sessionId, List<String> seatCodes) {
        List<Integer> indexes = new ArrayList<>();
        if (CollectionUtil.isEmpty(seatCodes)) {
            return indexes;
        }
        List<SeatArea> areas = listAreasWithOffset(sessionId);
        List<ShowSeat> seats = showSeatMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowSeat>()
                        .eq(ShowSeat::getSessionId, sessionId)
                        .in(ShowSeat::getSeatCode, seatCodes));
        for (ShowSeat seat : seats) {
            Integer index = locateIndex(areas, seat);
            if (Objects.nonNull(index)) {
                indexes.add(index);
            }
        }
        indexes.sort(Comparator.naturalOrder());
        return indexes;
    }

    @Override
    public long resolveTtlSeconds(Long sessionId) {
        ShowSession session = getById(sessionId);
        if (Objects.isNull(session) || Objects.isNull(session.getEndTime())) {
            return TimeUnit.HOURS.toSeconds(6);
        }
        long secondsUntilEnd = Duration.between(LocalDateTimeUtil.now(), session.getEndTime()).getSeconds();
        return Math.max(60L, secondsUntilEnd + Duration.ofDays(1).getSeconds());
    }

    private SessionDetail assemble(ShowSession session) {
        SessionDetail detail = new SessionDetail();
        detail.setSessionId(session.getId());
        detail.setShowId(session.getShowId());
        detail.setVenueId(session.getVenueId());
        detail.setSessionName(session.getSessionName());
        detail.setBeginTime(session.getBeginTime());
        detail.setEndTime(session.getEndTime());
        detail.setSaleBeginTime(session.getSaleBeginTime());
        detail.setSaleEndTime(session.getSaleEndTime());
        detail.setStatus(session.getStatus());
        detail.setSeatTotal(session.getSeatTotal());
        detail.setVersion(session.getVersion());
        Show show = showService.getById(session.getShowId());
        if (Objects.nonNull(show)) {
            detail.setShowName(show.getName());
            detail.setCategory(show.getCategory());
        }
        Venue venue = venueService.getById(session.getVenueId());
        if (Objects.nonNull(venue)) {
            detail.setVenueName(venue.getName());
            detail.setCity(venue.getCity());
            detail.setAddress(venue.getAddress());
        }
        detail.setAreas(listAreasWithOffset(session.getId()));
        return detail;
    }

    private List<ShowSeat> seatsOf(Long sessionId) {
        return showSeatMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ShowSeat>()
                        .eq(ShowSeat::getSessionId, sessionId));
    }

    /** 把 (分区, 排, 列) 换算成全场次唯一的座位索引 */
    private Integer locateIndex(List<SeatArea> areas, ShowSeat seat) {
        if (CollectionUtil.isEmpty(areas)) {
            return null;
        }
        for (SeatArea area : areas) {
            if (Objects.equals(area.getId(), seat.getAreaId())) {
                int withinArea = (seat.getRowNo() - 1) * area.getSeatPerRow() + (seat.getColNo() - 1);
                return area.getStartIndex() + withinArea;
            }
        }
        return null;
    }
}

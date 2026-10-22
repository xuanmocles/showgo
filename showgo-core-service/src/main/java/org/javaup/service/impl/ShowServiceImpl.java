package org.javaup.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.dto.ShowQueryDto;
import org.javaup.entity.Show;
import org.javaup.entity.ShowSession;
import org.javaup.entity.Venue;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.mapper.ShowMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IShowService;
import org.javaup.service.IShowSessionService;
import org.javaup.service.IVenueService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_SHOW;
import static org.javaup.utils.RedisConstants.CACHE_NULL_TTL;
import static org.javaup.utils.RedisConstants.CACHE_SHOW_TTL;

/**
 * 演出：列表走 DB，详情走「布隆过滤器 + 空值缓存 + Redis 缓存」。
 */
@Slf4j
@Service
public class ShowServiceImpl extends ServiceImpl<ShowMapper, Show> implements IShowService {

    @Resource
    private RedisCache redisCache;

    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;

    @Resource
    private IVenueService venueService;

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public List<Show> queryShows(ShowQueryDto queryDto) {
        List<Show> shows = baseQuery(queryDto)
                .orderByDesc(Show::getStatus)
                .orderByAsc(Show::getMinPrice)
                .page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        queryDto.getPage(), queryDto.getPageSize()))
                .getRecords();
        fillVenueInfo(shows);
        return shows;
    }

    @Override
    public long countShows(ShowQueryDto queryDto) {
        return baseQuery(queryDto).count();
    }

    private com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<Show> baseQuery(ShowQueryDto queryDto) {
        List<Long> venueIds = null;
        if (StrUtil.isNotBlank(queryDto.getCity())) {
            venueIds = venueService.lambdaQuery()
                    .eq(Venue::getCity, queryDto.getCity())
                    .list()
                    .stream()
                    .map(Venue::getId)
                    .collect(Collectors.toList());
            if (CollectionUtil.isEmpty(venueIds)) {
                venueIds = Collections.singletonList(-1L);
            }
        }
        return lambdaQuery()
                .eq(Show::getStatus, 1)
                .eq(Objects.nonNull(queryDto.getCategory()), Show::getCategory, queryDto.getCategory())
                .in(CollectionUtil.isNotEmpty(venueIds), Show::getVenueId, venueIds)
                .and(StrUtil.isNotBlank(queryDto.getKeyword()),
                        w -> w.like(Show::getName, queryDto.getKeyword())
                                .or()
                                .like(Show::getArtist, queryDto.getKeyword()));
    }

    @Override
    public Show queryById(Long id) {
        RedisKeyBuild cacheKey = RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOW_KEY, id);
        RedisKeyBuild nullKey = RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOW_NULL_KEY, id);

        Show cached = redisCache.get(cacheKey, Show.class);
        if (Objects.nonNull(cached)) {
            fillVenueInfo(Collections.singletonList(cached));
            return cached;
        }
        if (!bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOW).contains(String.valueOf(id))) {
            throw new ShowGoFrameException("演出不存在");
        }
        if (Boolean.TRUE.equals(redisCache.hasKey(nullKey))) {
            throw new ShowGoFrameException("演出不存在");
        }
        Show show = getById(id);
        if (Objects.isNull(show)) {
            redisCache.set(nullKey, "null", CACHE_NULL_TTL, TimeUnit.MINUTES);
            throw new ShowGoFrameException("演出不存在");
        }
        redisCache.set(cacheKey, show, CACHE_SHOW_TTL, TimeUnit.MINUTES);
        fillVenueInfo(Collections.singletonList(show));
        return show;
    }

    @Override
    public List<ShowSession> hotSessions(int limit) {
        Set<String> sessionIds = redisCache.getReverseRangeForSortedSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_HOT_RANK_KEY),
                0, Math.max(limit - 1, 0), String.class);
        if (CollectionUtil.isEmpty(sessionIds)) {
            return showSessionService.lambdaQuery()
                    .eq(ShowSession::getStatus, 2)
                    .orderByDesc(ShowSession::getBeginTime)
                    .last("limit " + limit)
                    .list();
        }
        List<Long> ids = sessionIds.stream().map(Long::valueOf).collect(Collectors.toList());
        return showSessionService.query().in("id", ids)
                .last("ORDER BY FIELD(id," + StrUtil.join(",", ids) + ")")
                .list();
    }

    /** 新增演出：写库后同步写入布隆过滤器，避免新演出被判为不存在 */
    @Transactional(rollbackFor = Exception.class)
    public Show saveShow(Show show) {
        show.setId(snowflakeIdGenerator.nextId());
        show.setCreateTime(LocalDateTime.now());
        show.setUpdateTime(LocalDateTime.now());
        save(show);
        bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOW).add(String.valueOf(show.getId()));
        return show;
    }

    private void fillVenueInfo(List<Show> shows) {
        if (CollectionUtil.isEmpty(shows)) {
            return;
        }
        List<Long> venueIds = shows.stream().map(Show::getVenueId).filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        if (CollectionUtil.isEmpty(venueIds)) {
            return;
        }
        Map<Long, Venue> venueMap = venueService.listByIds(venueIds).stream()
                .collect(Collectors.toMap(Venue::getId, Function.identity(), (a, b) -> a));
        for (Show show : shows) {
            Venue venue = venueMap.get(show.getVenueId());
            if (Objects.nonNull(venue)) {
                show.setVenueName(venue.getName());
                show.setCity(venue.getCity());
            }
        }
    }
}

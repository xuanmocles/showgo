package org.javaup.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.Venue;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.mapper.VenueMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IVenueService;
import org.javaup.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.javaup.utils.RedisConstants.CACHE_NULL_TTL;
import static org.javaup.utils.RedisConstants.CACHE_VENUE_TTL;
import static org.javaup.utils.RedisConstants.VENUE_GEO_KEY;

/**
 * 场馆：走「缓存 + 空值缓存」读链路，附近场馆用 Redis GEO 实现。
 */
@Slf4j
@Service
public class VenueServiceImpl extends ServiceImpl<VenueMapper, Venue> implements IVenueService {

    @Resource
    private RedisCache redisCache;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Venue queryById(Long id) {
        RedisKeyBuild key = RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_VENUE_KEY, id);
        RedisKeyBuild nullKey = RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_VENUE_NULL_KEY, id);
        Venue cached = redisCache.get(key, Venue.class);
        if (Objects.nonNull(cached)) {
            return cached;
        }
        Boolean isNull = redisCache.hasKey(nullKey);
        if (Boolean.TRUE.equals(isNull)) {
            throw new ShowGoFrameException("场馆不存在");
        }
        Venue venue = getById(id);
        if (Objects.isNull(venue)) {
            redisCache.set(nullKey, "null", CACHE_NULL_TTL, TimeUnit.MINUTES);
            throw new ShowGoFrameException("场馆不存在");
        }
        redisCache.set(key, venue, CACHE_VENUE_TTL, TimeUnit.MINUTES);
        return venue;
    }

    @Override
    public List<String> listCities() {
        return lambdaQuery().select(Venue::getCity).list().stream()
                .map(Venue::getCity)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public void loadVenueGeo() {
        List<Venue> venues = lambdaQuery()
                .isNotNull(Venue::getLongitude)
                .isNotNull(Venue::getLatitude)
                .list();
        for (Venue venue : venues) {
            stringRedisTemplate.opsForGeo().add(VENUE_GEO_KEY,
                    new org.springframework.data.geo.Point(venue.getLongitude(), venue.getLatitude()),
                    String.valueOf(venue.getId()));
        }
        log.info("场馆 GEO 初始化完成，共 {} 个", venues.size());
    }

    @Override
    public List<Venue> queryNearby(double x, double y, int page) {
        int from = (Math.max(page, 1) - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = Math.max(page, 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                VENUE_GEO_KEY,
                GeoReference.fromCoordinate(x, y),
                new Distance(50_000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (results == null) {
            return Collections.emptyList();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= from) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(result -> {
            String id = result.getContent().getName();
            ids.add(Long.valueOf(id));
            distanceMap.put(id, result.getDistance());
        });
        String orderedIds = StrUtil.join(",", ids);
        List<Venue> venues = query().in("id", ids)
                .last("ORDER BY FIELD(id," + orderedIds + ")")
                .list();
        for (Venue venue : venues) {
            Distance distance = distanceMap.get(String.valueOf(venue.getId()));
            if (Objects.nonNull(distance)) {
                venue.setDistance(distance.getValue() / 1000);
            }
        }
        return venues;
    }
}

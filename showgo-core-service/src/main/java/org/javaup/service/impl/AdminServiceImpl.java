package org.javaup.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.cache.SessionCacheInvalidator;
import org.javaup.core.RedisKeyManage;
import org.javaup.dto.AdminLoginDto;
import org.javaup.dto.AdminSessionCreateDto;
import org.javaup.dto.AdminShowQueryDto;
import org.javaup.dto.AdminShowSaveDto;
import org.javaup.entity.SeatArea;
import org.javaup.entity.Show;
import org.javaup.entity.ShowSeat;
import org.javaup.entity.ShowSession;
import org.javaup.entity.Venue;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.mapper.SeatAreaMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IAdminService;
import org.javaup.service.IShowSeatService;
import org.javaup.service.IShowService;
import org.javaup.service.IShowSessionService;
import org.javaup.service.IVenueService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_SESSION;
import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_SHOW;

/**
 * 管理端实现。
 *
 * <p>核心不只是 CRUD：新建场次要在同一事务里写场次、分区和座位，
 * 提交后再补布隆过滤器、预热 Redis 座位图、失效 C 端缓存，
 * 保证管理端刚发布的演出，用户端立刻可见且可售。</p>
 */
@Slf4j
@Service
public class AdminServiceImpl implements IAdminService {

    /** 单场次座位上限，防止误配置把 Redis 位图撑爆 */
    private static final int MAX_SEATS_PER_SESSION = 50_000;

    @Resource
    private IShowService showService;

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private IShowSeatService showSeatService;

    @Resource
    private IVenueService venueService;

    @Resource
    private SeatAreaMapper seatAreaMapper;

    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;

    @Resource
    private SessionCacheInvalidator sessionCacheInvalidator;

    @Resource
    private RedisCache redisCache;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:admin123}")
    private String adminPassword;

    @Value("${admin.token.ttl-seconds:7200}")
    private long adminTokenTtlSeconds;

    @Override
    public String login(AdminLoginDto loginDto) {
        if (!Objects.equals(adminUsername, loginDto.getUsername())
                || !Objects.equals(adminPassword, loginDto.getPassword())) {
            throw new ShowGoFrameException("账号或密码错误");
        }
        String token = IdUtil.fastSimpleUUID();
        redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.ADMIN_TOKEN_KEY, token),
                adminUsername, adminTokenTtlSeconds, TimeUnit.SECONDS);
        log.info("管理端登录成功 username={}", adminUsername);
        return token;
    }

    @Override
    public void logout(String token) {
        if (StrUtil.isNotBlank(token)) {
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.ADMIN_TOKEN_KEY, token));
        }
    }

    @Override
    public Page<Show> pageShows(AdminShowQueryDto queryDto) {
        Page<Show> page = new Page<>(
                queryDto.getPage() == null ? 1 : queryDto.getPage(),
                queryDto.getPageSize() == null ? 10 : queryDto.getPageSize());
        LambdaQueryWrapper<Show> wrapper = new LambdaQueryWrapper<Show>()
                .eq(Objects.nonNull(queryDto.getStatus()), Show::getStatus, queryDto.getStatus())
                .and(StrUtil.isNotBlank(queryDto.getKeyword()),
                        w -> w.like(Show::getName, queryDto.getKeyword())
                                .or()
                                .like(Show::getArtist, queryDto.getKeyword()))
                .orderByDesc(Show::getId);
        return showService.page(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Show saveShow(AdminShowSaveDto saveDto) {
        Venue venue = venueService.getById(saveDto.getVenueId());
        if (Objects.isNull(venue)) {
            throw new ShowGoFrameException("场馆不存在");
        }
        Show show = new Show()
                .setName(saveDto.getName())
                .setVenueId(saveDto.getVenueId())
                .setCategory(saveDto.getCategory())
                .setArtist(saveDto.getArtist())
                .setPoster(saveDto.getPoster())
                .setDescription(saveDto.getDescription())
                .setDurationMinutes(saveDto.getDurationMinutes() == null ? 120 : saveDto.getDurationMinutes())
                .setMinPrice(saveDto.getMinPrice() == null ? 0L : saveDto.getMinPrice())
                .setStatus(saveDto.getStatus() == null ? 1 : saveDto.getStatus())
                .setUpdateTime(LocalDateTime.now());

        if (Objects.isNull(saveDto.getId())) {
            show.setId(snowflakeIdGenerator.nextId());
            show.setCreateTime(LocalDateTime.now());
            showService.save(show);
            bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOW).add(String.valueOf(show.getId()));
            log.info("管理端新增演出 id={} name={}", show.getId(), show.getName());
        } else {
            if (Objects.isNull(showService.getById(saveDto.getId()))) {
                throw new ShowGoFrameException("演出不存在");
            }
            show.setId(saveDto.getId());
            showService.updateById(show);
            log.info("管理端编辑演出 id={} name={}", show.getId(), show.getName());
        }
        invalidateShowCache(show.getId());
        return show;
    }

    @Override
    public void updateShowStatus(Long showId, Integer status) {
        if (!Objects.equals(status, 1) && !Objects.equals(status, 2) && !Objects.equals(status, 3)) {
            throw new ShowGoFrameException("非法的演出状态");
        }
        Show show = showService.getById(showId);
        if (Objects.isNull(show)) {
            throw new ShowGoFrameException("演出不存在");
        }
        showService.lambdaUpdate()
                .set(Show::getStatus, status)
                .set(Show::getUpdateTime, LocalDateTime.now())
                .eq(Show::getId, showId)
                .update();
        invalidateShowCache(showId);
        log.info("管理端修改演出状态 id={} status={}", showId, status);
    }

    @Override
    public void deleteShow(Long showId) {
        long sessionCount = showSessionService.lambdaQuery()
                .eq(ShowSession::getShowId, showId)
                .count();
        if (sessionCount > 0) {
        throw new ShowGoFrameException(
                "该演出已有 " + sessionCount + " 个场次，不能直接删除；如需下线请改为「下架」");
        }
        showService.removeById(showId);
        invalidateShowCache(showId);
        log.info("管理端删除演出 id={}", showId);
    }

    @Override
    public List<Venue> listVenues() {
        return venueService.lambdaQuery()
                .eq(Venue::getStatus, 1)
                .orderByAsc(Venue::getCity)
                .list();
    }

    @Override
    public List<ShowSession> listSessions(Long showId) {
        return showSessionService.lambdaQuery()
                .eq(ShowSession::getShowId, showId)
                .orderByAsc(ShowSession::getBeginTime)
                .list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ShowSession createSession(AdminSessionCreateDto createDto) {
        Show show = showService.getById(createDto.getShowId());
        if (Objects.isNull(show)) {
            throw new ShowGoFrameException("演出不存在");
        }
        validateTimes(createDto);
        int seatTotal = validateAndSumSeats(createDto.getAreas());

        long sessionId = snowflakeIdGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();
        ShowSession session = new ShowSession()
                .setId(sessionId)
                .setShowId(show.getId())
                .setVenueId(show.getVenueId())
                .setSessionName(createDto.getSessionName())
                .setBeginTime(createDto.getBeginTime())
                .setEndTime(createDto.getEndTime())
                .setSaleBeginTime(createDto.getSaleBeginTime())
                .setSaleEndTime(createDto.getSaleEndTime())
                .setSeatTotal(seatTotal)
                .setSeatAvailable(seatTotal)
                .setStatus(createDto.getStatus() == null ? 1 : createDto.getStatus())
                .setVersion(0)
                .setCreateTime(now)
                .setUpdateTime(now);
        showSessionService.save(session);

        List<ShowSeat> seats = new ArrayList<>(seatTotal);
        for (AdminSessionCreateDto.SeatAreaConfig config : createDto.getAreas()) {
            long areaId = snowflakeIdGenerator.nextId();
            SeatArea area = new SeatArea()
                    .setId(areaId)
                    .setSessionId(sessionId)
                    .setAreaNo(config.getAreaNo())
                    .setAreaCode(config.getAreaCode())
                    .setAreaName(config.getAreaName())
                    .setAreaType(config.getAreaType() == null ? 1 : config.getAreaType())
                    .setPrice(config.getPrice())
                    .setColor(config.getColor())
                    .setRowCount(config.getRowCount())
                    .setSeatPerRow(config.getSeatPerRow())
                    .setCreateTime(now)
                    .setUpdateTime(now);
            seatAreaMapper.insert(area);

            for (int row = 1; row <= config.getRowCount(); row++) {
                for (int col = 1; col <= config.getSeatPerRow(); col++) {
                    seats.add(new ShowSeat()
                            .setId(snowflakeIdGenerator.nextId())
                            .setSessionId(sessionId)
                            .setAreaId(areaId)
                            .setAreaCode(config.getAreaCode())
                            .setRowNo(row)
                            .setColNo(col)
                            .setSeatCode(String.format("%s-%02d-%02d", config.getAreaCode(), row, col))
                            .setSeatType(area.getAreaType())
                            .setPrice(config.getPrice())
                            .setStatus(0)
                            .setCreateTime(now)
                            .setUpdateTime(now));
                }
            }
        }
        showSeatService.saveBatch(seats, 500);

        // 事务提交后再做缓存侧动作，避免数据库还没提交就读不到数据
        afterCommit(() -> {
            bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOW).add(String.valueOf(show.getId()));
            bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SESSION).add(String.valueOf(sessionId));
            sessionCacheInvalidator.publishInvalidate(sessionId, "session created");
            showSessionService.warmUpSeatMap(sessionId);
            log.info("管理端创建场次完成 sessionId={} 座位数={}", sessionId, seatTotal);
        });
        return session;
    }

    @Override
    public void updateSessionStatus(Long sessionId, Integer status) {
        if (status == null || status < 1 || status > 6) {
            throw new ShowGoFrameException("非法的场次状态");
        }
        if (Objects.isNull(showSessionService.getById(sessionId))) {
            throw new ShowGoFrameException("场次不存在");
        }
        showSessionService.lambdaUpdate()
                .set(ShowSession::getStatus, status)
                .set(ShowSession::getUpdateTime, LocalDateTime.now())
                .eq(ShowSession::getId, sessionId)
                .update();
        sessionCacheInvalidator.publishInvalidate(sessionId, "session status changed");
        log.info("管理端修改场次状态 sessionId={} status={}", sessionId, status);
    }

    @Override
    public int recomputeStock(Long sessionId) {
        if (Objects.isNull(showSessionService.getById(sessionId))) {
            throw new ShowGoFrameException("场次不存在");
        }
        long available = showSeatService.lambdaQuery()
                .eq(ShowSeat::getSessionId, sessionId)
                .eq(ShowSeat::getStatus, 0)
                .count();
        showSessionService.lambdaUpdate()
                .set(ShowSession::getSeatAvailable, (int) available)
                .set(ShowSession::getUpdateTime, LocalDateTime.now())
                .eq(ShowSession::getId, sessionId)
                .update();
        redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.SESSION_STOCK_TAG_KEY, sessionId),
                String.valueOf(available),
                showSessionService.resolveTtlSeconds(sessionId),
                TimeUnit.SECONDS);
        sessionCacheInvalidator.publishInvalidate(sessionId, "stock recomputed");
        log.info("管理端重算余票 sessionId={} available={}", sessionId, available);
        return (int) available;
    }

    private void validateTimes(AdminSessionCreateDto dto) {
        LocalDateTime now = LocalDateTime.now();
        if (!dto.getBeginTime().isBefore(dto.getEndTime())) {
            throw new ShowGoFrameException("开演时间必须早于散场时间");
        }
        if (!dto.getSaleBeginTime().isBefore(dto.getSaleEndTime())) {
            throw new ShowGoFrameException("开售时间必须早于停售时间");
        }
        if (dto.getSaleEndTime().isAfter(dto.getBeginTime())) {
            throw new ShowGoFrameException("停售时间不能晚于开演时间");
        }
        if (!dto.getEndTime().isAfter(now)) {
            throw new ShowGoFrameException("散场时间必须晚于当前时间");
        }
    }

    private int validateAndSumSeats(List<AdminSessionCreateDto.SeatAreaConfig> areas) {
        Set<String> codes = new HashSet<>();
        int total = 0;
        for (AdminSessionCreateDto.SeatAreaConfig area : areas) {
            if (!codes.add(area.getAreaCode())) {
                throw new ShowGoFrameException("分区编码重复：" + area.getAreaCode());
            }
            total += Math.multiplyExact(area.getRowCount(), area.getSeatPerRow());
        }
        if (total > MAX_SEATS_PER_SESSION) {
            throw new ShowGoFrameException("单场次座位数不能超过 " + MAX_SEATS_PER_SESSION);
        }
        return total;
    }

    private void invalidateShowCache(Long showId) {
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOW_KEY, showId));
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOW_NULL_KEY, showId));
    }

    /** 事务提交后执行；没有事务时直接执行 */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(action);
                }
            });
        } else {
            runQuietly(action);
        }
    }

    private void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("管理端提交后处理失败（数据已提交，等待对账/缓存过期收敛）", e);
        }
    }
}

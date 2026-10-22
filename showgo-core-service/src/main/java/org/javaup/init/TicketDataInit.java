package org.javaup.init;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.Show;
import org.javaup.entity.ShowSession;
import org.javaup.enums.SessionStatus;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.redis.RedisCache;
import org.javaup.service.IShowService;
import org.javaup.service.IShowSessionService;
import org.javaup.service.IVenueService;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_SESSION;
import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_SHOW;

/**
 * 启动初始化：按顺序完成「场馆 GEO → 布隆过滤器 → 在售场次座位图预热」。
 *
 * <p>放在同一个初始化器里而不是拆成多个 @PostConstruct，是为了保证执行顺序可控，
 * 避免座位图预热时布隆过滤器还没准备好。</p>
 */
@Slf4j
@Component
public class TicketDataInit {

    @Resource
    private IVenueService venueService;

    @Resource
    private IShowService showService;

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;

    @Resource
    private RedisCache redisCache;

    @PostConstruct
    public void init() {
        try {
            venueService.loadVenueGeo();
        } catch (Exception e) {
            log.warn("场馆 GEO 初始化失败", e);
        }
        initBloomFilters();
        warmUpSeatMaps();
    }

    private void initBloomFilters() {
        log.info("========== 初始化演出布隆过滤器 ==========");
        List<Show> shows = showService.list();
        for (Show show : shows) {
            bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOW).add(String.valueOf(show.getId()));
        }
        log.info("========== 初始化场次布隆过滤器 ==========");
        List<ShowSession> sessions = showSessionService.lambdaQuery()
                .select(ShowSession::getId)
                .list();
        for (ShowSession session : sessions) {
            bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SESSION).add(String.valueOf(session.getId()));
        }
    }

    private void warmUpSeatMaps() {
        log.info("========== 预热在售场次座位图 ==========");
        List<ShowSession> onSaleSessions = showSessionService.lambdaQuery()
                .in(ShowSession::getStatus, SessionStatus.WAIT_SALE.getCode(), SessionStatus.ON_SALE.getCode())
                .list();
        int warmed = 0;
        for (ShowSession session : onSaleSessions) {
            try {
                showSessionService.warmUpSeatMap(session.getId());
                warmed++;
            } catch (Exception e) {
                log.warn("预热座位图失败 sessionId={}", session.getId(), e);
            }
        }
        log.info("========== 座位图预热完成，共 {} 个场次 ==========", warmed);
    }
}

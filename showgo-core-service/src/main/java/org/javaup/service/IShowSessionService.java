package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.entity.SeatArea;
import org.javaup.entity.ShowSession;
import org.javaup.model.SessionDetail;

import java.util.List;

public interface IShowSessionService extends IService<ShowSession> {

    /** 场次聚合详情：本地缓存 -> Redis -> 布隆过滤器 -> 空值缓存 -> 锁 + DB */
    SessionDetail queryDetail(Long sessionId);

    /** 查询演出的所有场次 */
    List<ShowSession> listByShow(Long showId);

    /** 初始化座位图：写入 Redis BitField 索引、余票计数、索引->座位编码映射 */
    void warmUpSeatMap(Long sessionId);

    /** 按数据库状态重建 Redis 座位图（对账补偿用） */
    void rebuildSeatMap(Long sessionId);

    /** 查询票档，并计算每个分区在座位图中的起始索引 */
    List<SeatArea> listAreasWithOffset(Long sessionId);

    /** 座位索引 -> 座位编码 */
    List<String> seatCodesOf(Long sessionId, List<Integer> seatIndexes);

    /** 座位编码 -> 座位索引 */
    List<Integer> seatIndexesOf(Long sessionId, List<String> seatCodes);

    /** 场次相关 Key 的过期时间：散场时间 + 1 天 */
    long resolveTtlSeconds(Long sessionId);
}

package org.javaup.service;

import org.javaup.dto.SeatLockDto;
import org.javaup.dto.SeatRecommendDto;
import org.javaup.enums.BusinessType;
import org.javaup.vo.SeatLockVo;
import org.javaup.vo.SeatMapVo;
import org.javaup.vo.SeatRecommendVo;

import java.util.List;

public interface ISeatService {

    /** 查询整场座位图（状态来自 Redis BitField） */
    SeatMapVo querySeatMap(Long sessionId);

    /** 推荐连座：一次 GET + Lua 内解码扫描 */
    SeatRecommendVo recommend(SeatRecommendDto recommendDto);

    /** 锁座：校验状态与限购后原子置为锁定态，并登记延迟释放 */
    SeatLockVo lock(SeatLockDto seatLockDto);

    /** 释放座位（锁座超时 / 未支付关单 / 退票共用），余票前后值由本方法自行记录 */
    void release(Long sessionId, Long userId, List<Integer> seatIndexes, Long orderId,
                 BusinessType businessType);

    /** 座位索引 -> 座位编码 */
    List<String> seatCodesOf(Long sessionId, List<Integer> seatIndexes);

    /** 座位编码 -> 座位索引 */
    List<Integer> seatIndexesOf(Long sessionId, List<String> seatCodes);
}

package org.javaup.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场次座位图。
 */
@Data
public class SeatMapVo {

    private Long sessionId;

    private Long showId;

    private String showName;

    private String venueName;

    private String address;

    private String sessionName;

    private LocalDateTime beginTime;

    private LocalDateTime saleEndTime;

    /** 从 Redis 余票计数读取 */
    private Integer seatAvailable;

    private Integer seatTotal;

    private List<SeatAreaVo> areas;
}

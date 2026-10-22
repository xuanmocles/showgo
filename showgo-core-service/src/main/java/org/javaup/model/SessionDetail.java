package org.javaup.model;

import lombok.Data;
import org.javaup.entity.SeatArea;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 场次聚合详情：演出 + 场馆 + 票档 + 开售规则，是 Redis / 本地缓存的缓存对象。
 */
@Data
public class SessionDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;

    private Long showId;

    private String showName;

    private Integer category;

    private Long venueId;

    private String venueName;

    private String city;

    private String address;

    private String sessionName;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    private LocalDateTime saleBeginTime;

    private LocalDateTime saleEndTime;

    private Integer status;

    private Integer seatTotal;

    /** 最低会员等级要求，为空表示不限制 */
    private Integer minLevel;

    /** 允许的会员等级，逗号分隔，为空表示不限制 */
    private String allowedLevels;

    private Integer version;

    /** 票档，同时携带每个分区在座位图中的起始索引 */
    private List<SeatArea> areas;
}

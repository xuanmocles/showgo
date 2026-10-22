package org.javaup.vo;

import lombok.Data;

import java.util.List;

/**
 * 连座推荐结果。
 */
@Data
public class SeatRecommendVo {

    private Long areaId;

    private String areaCode;

    private String areaName;

    private Long price;

    /** 分区内座位索引 */
    private List<Integer> seatIndexes;

    /** 座位编码，如 A-03-12 */
    private List<String> seatCodes;
}

package org.javaup.vo;

import lombok.Data;

import java.util.List;

/**
 * 座位图分区：以「行 -> 列」的二维状态数组返回，前端按坐标渲染座位。
 */
@Data
public class SeatAreaVo {

    private Long areaId;

    private String areaCode;

    private String areaName;

    private Integer areaType;

    private Long price;

    private String color;

    private Integer rowCount;

    private Integer seatPerRow;

    private Integer startIndex;

    /** 行 -> 每列状态：0可售 1锁定 2已售 3不可用 */
    private List<List<Integer>> seats;
}

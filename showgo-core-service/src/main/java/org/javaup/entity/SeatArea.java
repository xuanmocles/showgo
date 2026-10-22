package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 场次分区票档。座位图以「分区」为单位组织成矩阵。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seat_area")
public class SeatArea implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private Long sessionId;

    /** 分区序号，从 1 开始，决定该分区在座位图 BitField 中的偏移 */
    private Integer areaNo;

    private String areaCode;

    private String areaName;

    /** 1普通 2VIP 3无障碍 */
    private Integer areaType;

    /** 票档单价，单位分 */
    private Long price;

    private String color;

    private Integer rowCount;

    private Integer seatPerRow;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 该分区起始座位索引 */
    @TableField(exist = false)
    private Integer startIndex;

    @TableField(exist = false)
    private Integer seatCount;
}

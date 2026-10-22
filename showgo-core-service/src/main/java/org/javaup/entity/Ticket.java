package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 演出票，一单多票，一票一座。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket")
public class Ticket implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private Long orderId;

    private Long userId;

    private Long showId;

    private Long sessionId;

    private Long seatId;

    private String seatCode;

    private String areaName;

    private Long price;

    /** 1有效 2已退票 3已入场 */
    private Integer status;

    private LocalDateTime checkInTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

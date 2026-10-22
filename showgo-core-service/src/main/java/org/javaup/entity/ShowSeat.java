package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 场次座位。线上以 Redis BitField 为准，本表在出票事务中落库用于对账与明细查询。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_show_seat")
public class ShowSeat implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private Long sessionId;

    private Long areaId;

    private String areaCode;

    private Integer rowNo;

    private Integer colNo;

    private String seatCode;

    /** 1普通 2VIP 3无障碍 */
    private Integer seatType;

    private Long price;

    /** 0可售 1锁定 2已售 3不可用 */
    private Integer status;

    private String lockToken;

    private Long lockUserId;

    private LocalDateTime lockExpireTime;

    private Long orderId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

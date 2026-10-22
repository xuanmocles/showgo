package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 锁座流水。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seat_lock_log")
public class SeatLockLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private Long sessionId;

    private Long userId;

    private String seatCodes;

    private String lockToken;

    private Long traceId;

    /** 1已锁定 2已下单 3主动释放 4超时释放 */
    private Integer status;

    private LocalDateTime expireTime;

    private LocalDateTime releaseTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

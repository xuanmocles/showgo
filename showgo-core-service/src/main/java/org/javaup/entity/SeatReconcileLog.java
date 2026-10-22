package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 座位变更对账流水：Redis 扣减 / 恢复的每一步都留痕，供对账任务比对。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seat_reconcile_log")
public class SeatReconcileLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private Long orderId;

    private Long sessionId;

    private Long userId;

    private String seatCodes;

    private Long traceId;

    /** -1扣减 1恢复 */
    private Integer logType;

    /** 1成功 2超时 3失败 4主动取消 5退票 6锁座超时释放 */
    private Integer businessType;

    private Integer beforeQty;

    private Integer changeQty;

    private Integer afterQty;

    private String detail;

    private Integer reconciliationStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 座位回滚失败记录：重试耗尽后落库并告警。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_rollback_failure_log")
public class RollbackFailureLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private Long orderId;

    private Long userId;

    private Long sessionId;

    private String detail;

    private Integer resultCode;

    private Long traceId;

    private Integer retryAttempts;

    private String source;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

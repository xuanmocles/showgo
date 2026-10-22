package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表：业务事务提交的同时落一条待投递消息，由定时投递器保证最终送达。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_local_message")
public class LocalMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private String messageUuid;

    private Long bizId;

    private String bizType;

    private String topic;

    private String payload;

    /** 0待投递 1已投递 2已确认 3投递失败 */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

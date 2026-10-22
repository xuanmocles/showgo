package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 手机号与用户的映射，按手机号哈希分片。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_phone")
public class UserPhone implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "phone")
    private String phone;

    private Long userId;

    private LocalDateTime createTime;
}

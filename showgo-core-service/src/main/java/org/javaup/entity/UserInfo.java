package org.javaup.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户资料，等级用于「优先购票」资格校验。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "user_id")
    private Long userId;

    private String city;

    private String introduce;

    private Integer gender;

    private LocalDate birthday;

    /** 观演积分 */
    private Integer credits;

    /** 会员等级 1-10 */
    private Integer level;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

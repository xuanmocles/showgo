package org.javaup.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理端登录。
 */
@Data
public class AdminLoginDto {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}

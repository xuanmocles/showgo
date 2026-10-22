package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.dto.LoginFormDTO;
import org.javaup.dto.UserDTO;
import org.javaup.entity.User;

public interface IUserService extends IService<User> {

    /** 发送登录验证码（示例项目直接打日志，接入短信网关即可） */
    void sendCode(String phone);

    /** 验证码登录，手机号首次登录自动注册 */
    String login(LoginFormDTO loginForm);

    UserDTO getCurrentUser();
}

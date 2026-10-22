package org.javaup.controller;

import jakarta.annotation.Resource;
import org.javaup.dto.LoginFormDTO;
import org.javaup.dto.Result;
import org.javaup.entity.UserInfo;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IUserService;
import org.javaup.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口：验证码登录、个人资料。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @PostMapping("/code")
    public Result<Void> sendCode(@RequestParam("phone") String phone) {
        userService.sendCode(phone);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginFormDTO loginForm) {
        return Result.ok(userService.login(loginForm));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<org.javaup.dto.UserDTO> me() {
        return Result.ok(UserHolder.getUser());
    }

    @GetMapping("/info")
    public Result<UserInfo> info() {
        UserInfo userInfo = userInfoService.getByUserId(UserHolder.getUser().getId());
        return Result.ok(userInfo);
    }

    @PutMapping("/info")
    public Result<Boolean> updateInfo(@RequestBody UserInfo userInfo) {
        return Result.ok(userInfoService.updateUserInfo(userInfo));
    }
}

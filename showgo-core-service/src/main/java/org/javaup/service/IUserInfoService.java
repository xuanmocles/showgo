package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.entity.UserInfo;

public interface IUserInfoService extends IService<UserInfo> {

    /** 查询用户资料，带 Redis 缓存 */
    UserInfo getByUserId(Long userId);

    /** 更新资料，同时维护「等级 -> 用户集合」索引，用于开售前定向提醒 */
    boolean updateUserInfo(UserInfo userInfo);
}

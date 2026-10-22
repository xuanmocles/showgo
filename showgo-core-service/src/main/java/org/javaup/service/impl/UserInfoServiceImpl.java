package org.javaup.service.impl;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.UserInfo;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.mapper.UserInfoMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IUserInfoService;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.javaup.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.DistributedLockConstants.UPDATE_USER_INFO_LOCK;

/**
 * 用户资料与会员等级。
 */
@Slf4j
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    @Resource
    private RedisCache redisCache;

    @Override
    @ServiceLock(lockType = LockType.Read, name = UPDATE_USER_INFO_LOCK, keys = {"#userId"})
    public UserInfo getByUserId(Long userId) {
        RedisKeyBuild key = RedisKeyBuild.createRedisKey(RedisKeyManage.USER_INFO_KEY, userId);
        UserInfo cached = redisCache.get(key, UserInfo.class);
        if (Objects.nonNull(cached)) {
            return cached;
        }
        UserInfo userInfo = lambdaQuery().eq(UserInfo::getUserId, userId).one();
        if (Objects.nonNull(userInfo)) {
            redisCache.set(key, userInfo, 30, TimeUnit.MINUTES);
        }
        return userInfo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUserInfo(UserInfo userInfo) {
        Long currentUserId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        if (Objects.isNull(currentUserId)) {
            throw new ShowGoFrameException("请先登录");
        }
        userInfo.setUserId(currentUserId);
        userInfo.setLevel(null);
        userInfo.setCredits(null);
        userInfo.setUpdateTime(LocalDateTimeUtil.now());

        UserInfo existed = getById(currentUserId);
        boolean result = updateById(userInfo);
        redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.USER_INFO_KEY, currentUserId));
        if (result && Objects.nonNull(existed) && Objects.nonNull(existed.getLevel())) {
            // 维护等级索引，供开售前定向提醒使用
            redisCache.addForSet(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.USER_LEVEL_MEMBERS_TAG_KEY, existed.getLevel()),
                    String.valueOf(currentUserId));
        }
        return result;
    }
}

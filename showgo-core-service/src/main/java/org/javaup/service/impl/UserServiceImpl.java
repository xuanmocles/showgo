package org.javaup.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.dto.LoginFormDTO;
import org.javaup.dto.UserDTO;
import org.javaup.entity.User;
import org.javaup.entity.UserInfo;
import org.javaup.entity.UserPhone;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.mapper.UserMapper;
import org.javaup.mapper.UserPhoneMapper;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IUserService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.RegexUtils;
import org.javaup.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.javaup.utils.RedisConstants.LOGIN_CODE_KEY;
import static org.javaup.utils.RedisConstants.LOGIN_CODE_TTL;
import static org.javaup.utils.RedisConstants.LOGIN_USER_KEY;
import static org.javaup.utils.RedisConstants.LOGIN_USER_TTL;
import static org.javaup.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 用户：验证码登录，首次登录自动注册，登录态存在 Redis 共享 Session 中。
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserPhoneMapper userPhoneMapper;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public void sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new ShowGoFrameException("手机号格式不正确");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 真实环境替换为短信网关
        log.info("[登录验证码] phone={} code={}", phone, code);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new ShowGoFrameException("手机号格式不正确");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            throw new ShowGoFrameException("验证码错误或已过期");
        }
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

        UserPhone userPhone = userPhoneMapper.selectById(phone);
        User user;
        if (Objects.isNull(userPhone)) {
            user = register(phone);
        } else {
            user = getById(userPhone.getUserId());
            if (Objects.isNull(user)) {
                throw new ShowGoFrameException("用户不存在");
            }
        }

        String token = cn.hutool.core.util.IdUtil.fastSimpleUUID();
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        userDTO.setIcon(user.getIcon());

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, Map.of(
                "id", String.valueOf(userDTO.getId()),
                "nickName", StrUtil.blankToDefault(userDTO.getNickName(), ""),
                "icon", StrUtil.blankToDefault(userDTO.getIcon(), "")));
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return token;
    }

    @Override
    public UserDTO getCurrentUser() {
        return UserHolder.getUser();
    }

    private User register(String phone) {
        long userId = snowflakeIdGenerator.nextId();
        User user = new User()
                .setId(userId)
                .setPhone(phone)
                .setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(8))
                .setIcon("/imgs/icons/user-icon.png")
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        save(user);

        userPhoneMapper.insert(new UserPhone()
                .setPhone(phone)
                .setUserId(userId)
                .setCreateTime(LocalDateTime.now()));

        userInfoService.save(new UserInfo()
                .setUserId(userId)
                .setCity("")
                .setGender(0)
                .setCredits(0)
                .setLevel(1)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now()));
        return user;
    }
}

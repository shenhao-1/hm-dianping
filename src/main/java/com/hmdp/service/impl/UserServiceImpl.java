package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.PasswordEncoder;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 *  Service implementation
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号格式，格式不正确直接返回错误。
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        // 2. 生成 6 位数字验证码，并保存到 Redis，过期时间使用统一常量。
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 3. 当前项目未接入真实短信平台，本地开发阶段把验证码写入日志用于测试。
        log.debug("发送短信验证码成功，手机号：{}，验证码：{}", phone, code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 先校验手机号，后续验证码登录和密码登录都依赖合法手机号查询用户。
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        User user;
        if (StrUtil.isNotBlank(loginForm.getCode())) {
            // 2. 验证码登录：从 Redis 读取验证码，并与用户提交的验证码比较。
            String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
            if (StrUtil.isBlank(cacheCode) || !cacheCode.equals(loginForm.getCode())) {
                return Result.fail("验证码错误");
            }

            // 3. 验证码正确后查询用户。用户不存在时，说明是首次登录，自动注册新用户。
            user = query().eq("phone", phone).one();
            if (user == null) {
                user = createUserWithPhone(phone);
            }
            // 4. 验证码只允许使用一次，登录成功后立即删除。
            stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        } else if (StrUtil.isNotBlank(loginForm.getPassword())) {
            // 兼容密码登录：根据手机号查用户，再校验加密密码。
            user = query().eq("phone", phone).one();
            if (user == null || !PasswordEncoder.matches(user.getPassword(), loginForm.getPassword())) {
                return Result.fail("手机号或密码错误");
            }
        } else {
            return Result.fail("验证码或密码不能为空");
        }

        // 5. 生成登录 token，只把脱敏后的 UserDTO 写入 Redis 和 session。
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String token = UUID.randomUUID().toString(true);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        session.setAttribute("user", userDTO);
        return Result.ok(token);
    }

    @Override
    public Result logout(String token, HttpSession session) {
        // 退出时同时清理 Redis token 和 session 中的用户信息。
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        session.removeAttribute("user");
        return Result.ok();
    }

    @Override
    public Result sign() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("\u8bf7\u5148\u767b\u5f55");
        }

        LocalDateTime now = LocalDateTime.now();
        String key = USER_SIGN_KEY + user.getId() + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int offset = now.getDayOfMonth() - 1;
        stringRedisTemplate.opsForValue().setBit(key, offset, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("\u8bf7\u5148\u767b\u5f55");
        }

        LocalDateTime now = LocalDateTime.now();
        String key = USER_SIGN_KEY + user.getId() + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();
        List<Long> values = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0) == 0L) {
            return Result.ok(0);
        }

        long bitmap = values.get(0);
        int count = 0;
        while ((bitmap & 1L) == 1L) {
            count++;
            bitmap >>>= 1;
        }
        return Result.ok(count);
    }
    private User createUserWithPhone(String phone) {
        // 首次验证码登录时自动创建用户，昵称使用统一前缀加随机字符串。
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        save(user);
        return user;
    }
}
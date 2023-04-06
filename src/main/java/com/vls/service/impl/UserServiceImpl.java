package com.vls.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vls.dto.LoginFormDTO;
import com.vls.dto.Result;
import com.vls.dto.UserDTO;
import com.vls.entity.User;
import com.vls.mapper.UserMapper;
import com.vls.service.IUserService;
import com.vls.utils.RedisConstants;
import com.vls.utils.RegexUtils;
import com.vls.utils.SystemConstants;
import com.vls.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.annotation.Resources;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.vls.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if(phoneInvalid){
            return Result.fail("手机号输入有误");
        }
        //2. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //4. 发送验证码
        log.info("发送验证码成功,为{} ",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号，
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误");
        }
        //校验验证码
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());
        if(code == null || !loginForm.getCode().equals(code)){
            return Result.fail("验证码错误");
        }

        //根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        //用户不存在，创建新用户
        if(user == null){
            user = createUserWithPhone(loginForm.getPhone());
        }
    //保存用户信息到redis
        //随机生成token作为令牌
        String token = UUID.randomUUID().toString(true);
        //将user对象转为Dto对象并转为hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //UserDto转 map
        Map<String, Object> map = BeanUtil.beanToMap(userDTO,
                new HashMap<>(),
                CopyOptions.create().setFieldValueEditor((filedName, filedValue) -> filedValue.toString()));
        //设置有效期30min,
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);//long 转string类型会出错。
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }


    @Override
    public Result sign() {
        //获取用户信息
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //获取本月的第几天，offset
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        //存入redis SETBIT key offset value
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);//bitMap下标从0 —— 30
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取用户信息
        Long userId = UserHolder.getUser().getId();
        //获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //获取本月的第几天，offset
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        //获取到今天为止所有的签到记录,是十进制数  BITFIELD sign:5:202203 GET u14 0
            //因为bitField可以有多种操作，所以是list集合
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)//几个比特位
                         ).valueAt(0)
        );

        if(result == null || result.isEmpty()){
            return Result.ok();
        }
        //循环遍历 位运算
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        while(true){
            if((num & 1) == 0){
                break;
            }else {
                count++;
            }

            num >>>= 1;
        }

        return Result.ok(count);
    }
}

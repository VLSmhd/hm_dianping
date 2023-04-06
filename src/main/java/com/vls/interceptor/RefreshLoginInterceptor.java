package com.vls.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.vls.dto.UserDTO;
import com.vls.utils.RedisConstants;
import com.vls.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.vls.utils.RedisConstants.LOGIN_USER_KEY;


public class RefreshLoginInterceptor implements HandlerInterceptor {


    private StringRedisTemplate stringRedisTemplate;
    //手动创建的类，没有依赖注入，需要通过构造函数的方式，注入redis
    public RefreshLoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取请求头的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
           return true;
        }
        String key = LOGIN_USER_KEY + token;
        //基于token获取redis用户信息
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(key);
        //判断用户是否存在
        if(userMap.isEmpty()){
            return true;
        }
        //查询到的hash数据转化为userDto
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(),  false);
        //保存到ThreadLocal
        UserHolder.saveUser(userDTO);
        //刷新token有效期
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}

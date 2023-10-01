package com.vls.annotation.aspect;


import com.vls.annotation.RateLimiter;
import com.vls.config.WebExceptionAdvice;
import com.vls.constants.LimitType;
import com.vls.utils.IPUtil;
import io.netty.util.internal.SuppressJava6Requirement;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@Slf4j
@Aspect
@Component
public class RateLimiterAspect {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 具体实现限流
     * @param point
     * @param rateLimiter
     * @throws Throwable
     */
    @SuppressWarnings(value = "unchecked")
    @Before("@annotation(rateLimiter)")//制作切面
    public void doBefore(JoinPoint point, RateLimiter rateLimiter) throws Throwable{
        // 在 {time} 秒内仅允许访问 {count} 次。
        int secends = rateLimiter.time();
        int limitCount = rateLimiter.count();
        //构造key
        String key = keyConstruct(rateLimiter.type(), point);

        ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();
        //添加本次访问
        long currentTimeMillis = System.currentTimeMillis();
        zSetOperations.add(key, String.valueOf(currentTimeMillis), currentTimeMillis);
        stringRedisTemplate.expire(key, secends, TimeUnit.SECONDS);//设定过期时间，自动删除
        //删zset
        zSetOperations.removeRangeByScore(key, 0, currentTimeMillis - secends * 1000);

        //统计个数
        Long curCount = zSetOperations.zCard(key);

        if(curCount > limitCount){
            log.error("[limit] 限制请求数'{}',当前请求数'{}',缓存key'{}'", limitCount, curCount, key);
            throw new RuntimeException("请求频繁，请稍后");
        }
    }

    /**
     * 构造redis的key
     * @param type
     * @param point
     * @return
     */
    private String keyConstruct(LimitType type, JoinPoint point) {
        StringBuilder key = new StringBuilder("rate_limit:");
        //从应用上下文中获取ip
        ServletRequestAttributes attributes = (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        String ipAddress = IPUtil.getIpAddress(request);

        key.append(ipAddress);

        //获取接口的名称以及方法名称
        MethodSignature signature = (MethodSignature)point.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = method.getDeclaringClass();

        key.append(":").append(targetClass.getName()).append(":").append(method.getName());

        return key.toString();
    }
}

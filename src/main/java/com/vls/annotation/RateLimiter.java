package com.vls.annotation;

import com.vls.constants.LimitType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)//注解的生命周期
@Target(ElementType.METHOD)//作用对象
public @interface RateLimiter {

    /**
     * 定义redis中限流key前缀 rate_limit:com.xxx.controller.HelloController-hello //HelloController中的hello方法
     */
    String key() default "rate_limit:";

    //限流时间
    int time() default 5;

    //限流次数
    int count() default 10;

    LimitType type() default LimitType.DEFAULT;
}

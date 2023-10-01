package com.vls.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP  = 1679050620L;

    private StringRedisTemplate stringRedisTemplate;
    //序列号的位数
    private static final int COUNT_BITS = 32;

    //构造器注入StringRedisTemplate
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long newSecond = now.toEpochSecond(ZoneOffset.UTC);//秒级
        long timeStamp = newSecond - BEGIN_TIMESTAMP;
        //生成序列号
        //获取当前日期，单位：天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));//:就是redis的分隔符，方便管理
        //自增长
        Long count = stringRedisTemplate.opsForValue()
                .increment("icr:" + keyPrefix + ":"+date);//自增有上限 2的64次方,这里前缀后面加上今天的日期时间,统计的是当天的请求访问量，不可能超过序列号的长度

        return timeStamp << COUNT_BITS | count;
    }

}

package com.vls.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    //业务名称
    private String name;

    private StringRedisTemplate stringRedisTemplate;

    //唯一线程id
    private static final String ID_PREFIX = UUID.randomUUID().toString();

    //提前加载好脚本
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT ;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
//        指定脚本路径
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }


    private static final String KEY_PREFIX = "lock:";
    /**
     * 尝试获取锁
     *
     * @param timeoutSec 锁的超时时间，
     * @return true为获得成功
     */
    @Override
    public boolean tryLock(Long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name), threadId);
    }

//    @Override
//    public void unlock() {
//
//
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        //判断锁是不是自己的
//        if(threadId.equals(id)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//
//    }
}

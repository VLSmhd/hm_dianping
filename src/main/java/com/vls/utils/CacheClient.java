package com.vls.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.vls.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.vls.utils.RedisConstants.CACHE_NULL_TTL;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;

    }

    /**
     *
     * @param keyPrefix
     * @param id
     * @param type  谁调用这个函数，未知，所以要指定class参数
     * @param dbFallBack
     * @param time
     * @param unit
     * @param <R>
     * @param <ID>
     * @return 返回值不确定
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack, Long time, TimeUnit unit ) {
        String key = keyPrefix + id;
        //从redis查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //未命中直接返回null(其实是默认命中，因为设限时期无限)
        if(StrUtil.isBlank(json)){
            return null;
        }
        //命中：判断缓存信息是否过期
        //反序列化获得redisData对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        //查看数据是否过期
        if(!redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return r;
        }
        //过期了，缓存重建，尝试获取互斥锁
        boolean tryLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        //查看是否获取锁
        //获取锁了，开启独立线程,查数据库,注意，这里还要检查缓存是否存在
        if(tryLock){
            //利用线程池。
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    //查询数据库
                    R r1 = dbFallBack.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(RedisConstants.LOCK_SHOP_KEY + id);
                }
            });
        }
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);//拆箱，防止空指针异常
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


    public <R,ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit ){
        String key = keyPrefix + id;
        //从redis查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //命中直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //没查到，说明JSON要么是空字符串要么是null
        //判断命中的值是否是空值
        if(json != null){
            return null;
        }
        //缓存重建
        //尝试获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean lock = tryLock(lockKey);
            //判断是否获取成功
            if(!lock){
                Thread.sleep(50);
                //递归重来
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            //获取成功
            //redis没有，直接去数据库查
            r = dbFallback.apply(id);
            //数据库没有，空值写入redis防止缓存穿透
            if(r == null){
                //**空值写入redis
                this.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库有就返回,并写入redis
            this.set(key, r,time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(lockKey);
        }
        return r;
    }

}

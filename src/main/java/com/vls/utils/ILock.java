package com.vls.utils;



//分布式锁接口
public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁的超时时间，
     * @return true为获得成功
     */
    boolean tryLock(Long timeoutSec);

    void unlock();
}

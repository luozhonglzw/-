package com.hmdp.utils;

public interface ILock {


    /**
     * 获取锁
     * @param timeoutSec 锁持有的时间 过期自动删除
     * @return true 获取成功 false 获取失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}

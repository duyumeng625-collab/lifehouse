package com.hmdp.utils;

//一个程序就是一个进程，一个进程内部有多个线程。一个进程是一个JVM（JVM就是把.class翻译成windows，linux等可以听懂的语言）
//多个JVM一起运行就是集群
//分布式锁：满足分布式系统或集群模式下多进程可见并且互斥的锁。所有JVM共用一个锁
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，过期自动释放
     * @return
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unLock();
}

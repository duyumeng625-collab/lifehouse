package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";//不同分布式里的线程id各自递增，有可能出现线程id相同的情况
    //提前加载 Lua 脚本，后面执行解锁操作时直接调用
   private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
   static {
       UNLOCK_SCRIPT = new DefaultRedisScript<>();
       UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
       UNLOCK_SCRIPT.setResultType(Long.class);//指定返回类型
   }
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程表示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁    SET key thread1 NX EX 10
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success); //自动拆箱有空指针的可能性
    }
    @Override
    public void unLock() {
        //使用lua编程语言可以实现原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

        }
    }

//    @Override
//    public void unLock() {
//        //判断当前的锁是否是自己的锁。如果是才可以删除锁
//        //但这不能保证原子性，也就是判断成功和删除不是一起执行的，
//        //如果判断成功之后阻塞，然后锁过期，等到执行删除已经不是自己的锁了，但是不会判断第二次，会把别的锁删除
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        if (threadId.equals(id)){
//        stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }


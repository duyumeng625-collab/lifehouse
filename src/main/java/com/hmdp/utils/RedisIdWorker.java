package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final long BEGIN_TIMESTAMP = 1767225600L; //定义的开始时间，时间戳
    private static final int COUNT_BITS = 32; //序列号为32位
    public long nextId(String keyPrefix){
        //1.生成时间戳:当前时间距离某个开始时间过去了多少秒
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestemp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));//希望每天的序列号从1开始计算
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        //3.拼接并返回:如果直接通过字符串连在一起，就不是long型了
        return  timestemp << COUNT_BITS | count;
    }


//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
//        long second = time.toEpochSecond(ZoneOffset.UTC);  //这里的计算是，Unix/Linux 和很多编程语言统一规定的时间起点
//        System.out.println("second="+second);
//    }

}

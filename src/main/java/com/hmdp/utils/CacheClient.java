package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * redis缓存 解决缓存穿透，雪崩，击穿
 */
@Service
@Component //是 Spring 框架中的一个核心注解，它的主要作用是将一个普通的 Java 类标记为 Spring 容器中的一个 Bean（也就是一个被 Spring 管理的对象）
public class CacheClient {
    //构造器注入，相比@Autowired,更好 尤其是类必须依赖某些组件才能工作时
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,
                JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓解缓存穿透
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
//        1.从redis查商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(key);
//        2.判断缓存是否命中
        if (StrUtil.isNotBlank(Json)) {
//           2.1缓存命中, 返回给前端
            return JSONUtil.toBean(Json, type);
        }
//        判断是否命中空值  存在但isBlank说明是空字符串
        if (Json != null){
            return null; //说明商铺信息不存在
        }
//        2.2 若未命中,根据id查数据库
        R r = dbFallback.apply(id);
//        3. 判断数据库是有该商铺
        if (r == null){
            //            若不存在,返回404
//            将空值写入redis,缓解缓存穿透
            stringRedisTemplate.opsForValue().set(key,"",time, unit);

            return null;//说明商铺不存在
        }
//            若存在,则将商铺写入redis
        this.set(key, r, time, unit);

        return r;
    }


    /**
     * 缓解缓存击穿
     */
//新建一个线程池，最多有十个线程同时工作
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id,Class<R> type,Function<ID,R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
//        1.从redis查商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
//        2.判断缓存是否命中
        if (StrUtil.isBlank(json)) {
//        2.1缓存未命中, 返回
            return null;
        }
//        2.2 若命中,判断缓存是否过期（逻辑时间）：
//        3.先把json反序列化为对象，拿出逻辑时间，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);//redisData里的数据不知道是Shop类型，被hutool认为是JSONObject格式
        LocalDateTime expireTime = redisData.getExpireTime();
//        3.1没过期就把数据信息返回给前端
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
//        3.2.如果过期就尝试拿互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
//        4.如果拿到了互斥锁，则开启独立线程，查数据,写回缓存，查完释放锁
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                   this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }

            });
        }
//        5.不管是否拿到互斥锁，都要把现在查的数据返回
        return r;

    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //删掉锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }
    //对于一些热点key要提前缓存，缓存重建

}


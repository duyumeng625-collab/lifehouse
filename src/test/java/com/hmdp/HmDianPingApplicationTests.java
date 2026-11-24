package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisCommand;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private CacheClient cacheClient;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY +1L,shop,10L, TimeUnit.SECONDS);
    }
    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    //同时生成100*300个id
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () ->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();//主线程暂停，等300个线程执行完，不然线程是并发执行的，无法计算运行时间
        long end = System.currentTimeMillis();
        System.out.println("time=" + (end - begin));
    }
    @Test
    //把数据存储到redis的zset中，以typeid为id，shopid为value，经纬度为score，这样就可以计算两地距离
    void loadShopData(){
        //1.查询店铺信息
        List<Shop> list = shopService.list();
        //2.店铺分组，以typeid为id，一样的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //1.从entry中取出，一个entry是一组
            Long typeId = entry.getKey();
            List<Shop> value = entry.getValue();
            String key = "shop:geo:" + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>();
            //2.写入redis
            for (Shop shop : value) {
//                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                  locations.add(new RedisGeoCommands.GeoLocation<>(
                          shop.getId().toString(),
                          new Point(shop.getX(),shop.getY())
                  ));
            }
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }



}

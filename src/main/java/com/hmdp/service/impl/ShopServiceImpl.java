package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 根据ID查询商铺
     * @param id
     * @return
     */
    @Override

    public Result queryById(Long id) {
        //缓存穿透:请求的id在redis和数据库都不存在,如果频繁请求,则每次都要访问数据库
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById, CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿:被高并发访问并且缓存重建业务较复杂的key失效
//        Shop shop = queryWithMutex(id);
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById, 20L,TimeUnit.SECONDS);
        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 更新商铺
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        String key = CACHE_SHOP_KEY + shop.getId();
//        1.更新数据库
        updateById(shop);
//        2.删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if (x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
            //2.根据坐标分页查询。去redis中。按照距离排序，得到shopId，distance
            int from = (current - 1 ) * SystemConstants.DEFAULT_PAGE_SIZE;
            int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
            GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                    "shop:geo:" + typeId,
                    new Circle(
                            new Point(x, y),      // 经度x，纬度y
                            new Distance(5000) // 半径5km
                    ),
                    RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                            .includeDistance() // 返回距离
                            .limit(end)        //查询全部
            );
            //3.解析出shopid，查询shop
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
                //3.1截取from ~ end
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from){
            //已经没有了，查完了
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> map = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result ->{    //每个result都是一个店铺
               //3.2获得shopid和距离
            String shopIdStr = result.getContent().getName();
            Distance distance = result.getDistance();
            Long shopId = Long.valueOf(shopIdStr);
            ids.add(shopId);
            map.put(shopIdStr,distance); //距离和id一一对应
        });
        //3.3根据id查shop
        String idStr = StrUtil.join(",", ids); //拼接id
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            Distance distance = map.get(shop.getId().toString());
            shop.setDistance((distance).getValue());
        }
        //返回
        return Result.ok(shops);



    }
}

    //新建一个线程池，最多有十个线程同时工作
/*    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
//        1.从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2.判断缓存是否命中
        if (StrUtil.isBlank(shopJson)) {
//        2.1缓存未命中, 返回
            return null;
        }
//        2.2 若命中,判断缓存是否过期（逻辑时间）：
//        3.先把json反序列化为对象，拿出逻辑时间，判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);//redisData里的数据不知道是Shop类型，被hutool认为是JSONObject格式
        LocalDateTime expireTime = redisData.getExpireTime();
//        3.1没过期就把数据信息返回给前端
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
//        3.2.如果过期就尝试拿互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
//        4.如果拿到了互斥锁，则开启独立线程，查数据,写回缓存，查完释放锁
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unLock(lockKey);
                }

            });
        }
//        5.不管是否拿到互斥锁，都要把现在查的数据返回
        return shop;


    }*/
/*    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
//        1.从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//           3.缓存命中, 返回给前端
            return shop;
        }
//        判断是否命中空值  存在但isBlank说明是空字符串
        if (shopJson != null){
            return null; //说明商铺信息不存在
        }
//        4.实现缓存重建
//        4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
//        4.2判断锁是否获取成功
            if (!isLock){
    //        4.3如果获取失败,表示锁被占用,等待一段时间之后重新查redis
                Thread.sleep(50);
                return queryWithMutex(id);
            }

//        4.4如果获取成功,根据id查数据,将数据写入redis,释放锁,返回值
            shop = getById(id);
            //这里让线程放慢速度,可以模拟高并发,不然第一个线程很快就处理完了,写入了redis
//            Thread.sleep(200);
//        5. 判断数据库是有该商铺
            if (shop == null){
                //若不存在,返回404
    //            将空值写入redis,缓解缓存穿透
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

                return null;//说明商铺不存在
            }
//         6.若存在,则将商铺写入redis
//        这里不能tostring,而是要转成标准的Json格式
            String shopString = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,shopString,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }*/


    //缓解缓存穿透
    /*public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
//        1.从redis查商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//           2.1缓存命中, 返回给前端
            return shop;
        }
//        判断是否命中空值  存在但isBlank说明是空字符串
        if (shopJson != null){
            return null; //说明商铺信息不存在
        }
//        2.2 若未命中,根据id查数据库
        Shop shop = getById(id);
//        3. 判断数据库是有该商铺
        if (shop == null){
            //            若不存在,返回404
//            将空值写入redis,缓解缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;//说明商铺不存在
        }
//            若存在,则将商铺写入redis
//        这里不能tostring,而是要转成标准的Json格式
        String shopString = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,shopString,CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }*/

    //如果锁key不存在,才会创建并写入,true表示拿到锁了,   如果存在则返回false,说明锁被占用
/*    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }*/

    //删掉锁
 /*   private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //对于一些热点key要提前缓存，缓存重建
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
//        1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
//        2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));*/



package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result quaryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;
//        1.从redis查询全部商品分类
        List<String> shopTypeListJson = stringRedisTemplate.opsForList().range(key, 0, -1);
//        2.判断是否为空
//        CollectionUtil hutool提供的集合工具类,快速开发,判空
        //Collections Java提供的,偏底层操作 排序,反转
        if (CollectionUtil.isNotEmpty(shopTypeListJson)) {
            //        3.若不为空,则返回
//            把list里的json字符串一个一个转成ShopType对象并排序,返回给前端
            List<ShopType> shopTypeList = shopTypeListJson.stream()
                            .map(json -> JSONUtil.toBean(json,ShopType.class))
                            .collect(Collectors.toList());
//            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeListJson.toString(), ShopType.class);
            Collections.sort(shopTypeList,(Comparator.comparingInt(ShopType::getSort)));//根据分类的sort字段排序
            return Result.ok(shopTypeList);
        }

//        4.若为空.则查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
//        5.判断数据库查询结果是否为空
        if (CollectionUtil.isEmpty(shopTypeList)) {
//        6.若为空,返回404
            return Result.fail("商铺类型不存在...");
        }
//        7.若不为空,则返回给redis,使用redis的List类型
        List<String> shopTypesJson  = shopTypeList.stream()
                .map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().leftPushAll(key,shopTypesJson);

//        8.返回给前端
        return Result.ok(shopTypeList);
    }
}

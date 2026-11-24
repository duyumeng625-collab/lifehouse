package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送短信验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
//        1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //        2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
//        3.如果符合生成验证码
        String code = RandomUtil.randomNumbers(6);
//        4..保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        5.发送验证码
        log.debug("发送短信验证码成功：{}",code);
//         6.返回
        return Result.ok();
    }

    /**
     * 登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
//        1.校验手机号，验证码
        if (RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机格式错误");
        }
        // 从redis获取验证码并校验
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !cacheCode.toString().equals(code)){
            //        2。如果不一致，返回错误信息
            return Result.fail("验证码错误");
        }
//        3.一致，根据手机号查询用户 MybatisPlus 可以直接实现单表的增删改查，不用自己写
        User user = query().eq("phone", phone).one();
//        4.判断用户是否存在
        if (user == null){
//        5.若不存在用户，则创建新用户，并保存到数据库
           user = createUserWithPhone(phone);
        }
//        6.把用户保存到redis
//        6.1随机生成token作为登录令牌
        String token = UUID.randomUUID().toString(true);
//        6.2将User对象转化为Hash格式存储
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        //把JAVA对象转化为map,其中id的值是long型,要转成string
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fileName,fileValue) -> fileValue.toString())
                );
//        6.3保存到redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
//        6.4设置有效期,长时间不访问就踢出
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
//        7.返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前用户
        Long id = UserHolder.getUser().getId();
        //2.获取当前时间
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id + keySuffix;
        //4.获取当前是第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth -1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前用户
        Long id = UserHolder.getUser().getId();
        //2.获取当前时间
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id + keySuffix;
        //4.获取当前是第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截至今天为止所有的签到记录,得到一个十进制的数字    BITFIELD sign:userid:yyyyMM  GET u9 0 从第一位开始到今天共九位
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);   //获得第一个元素   如果为0，则表明这个月没签到过
        if (num == null || num == 0){
            return Result.ok(0);
        }

        //循环遍历
        int count = 0;
        while (true) {
            //数字与1做与运算，最后一位bit位    判断是否为1
            if((num & 1) == 0){
                //为0 未签到
                break;
            }else {
                //为1 签到  count+1
               count++;
            }
            //数字右移一位，继续与运算
            num>>>=1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}

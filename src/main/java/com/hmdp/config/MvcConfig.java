package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
   public void addInterceptors(InterceptorRegistry interceptorRegistry){
        //使用拦截注册器注册一个登录拦截器, 并且排除一些不需要拦截的
        interceptorRegistry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"

                ).order(1);
//        token刷新拦截器
        //order越小越先执行
        interceptorRegistry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
    }
}

package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.service.UvService;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.UvInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UvService uvService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Parse the token for every request and refresh its Redis TTL when it is valid.
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        // Publishing, liking and querying the current user's blogs still require login.
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/error",
                        "/blog/hot",
                        "/blog/*",
                        "/blog/likes/**",
                        "/blog/of/user",
                        "/shop/**",
                        "/shop-type/**",
                        "/voucher/**",
                        "/upload/**"
                )
                .order(1);

        // Count every visitor once per day. Exclude statistics queries to avoid self-counting.
        registry.addInterceptor(new UvInterceptor(uvService))
                .addPathPatterns("/**")
                .excludePathPatterns("/stats/**", "/error")
                .order(2);
    }
}
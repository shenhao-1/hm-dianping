package com.hmdp.service;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.UV_KEY;
import static com.hmdp.utils.RedisConstants.UV_TTL_DAYS;

@Service
public class UvService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void recordVisit(HttpServletRequest request) {
        String key = buildKey(LocalDate.now());
        String visitor = resolveVisitor(request);
        stringRedisTemplate.opsForHyperLogLog().add(key, visitor);
        stringRedisTemplate.expire(key, UV_TTL_DAYS, TimeUnit.DAYS);
    }

    public long count(LocalDate date) {
        Long count = stringRedisTemplate.opsForHyperLogLog().size(buildKey(date));
        return count == null ? 0L : count;
    }

    private String buildKey(LocalDate date) {
        return UV_KEY + date.format(DATE_FORMATTER);
    }

    private String resolveVisitor(HttpServletRequest request) {
        UserDTO user = UserHolder.getUser();
        if (user != null && user.getId() != null) {
            return "user:" + user.getId();
        }
        return "ip:" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.trim().isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.trim().isEmpty()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
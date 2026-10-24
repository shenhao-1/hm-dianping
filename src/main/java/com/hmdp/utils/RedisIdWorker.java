package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.hmdp.utils.RedisConstants.INCR_ID_KEY;

@Component
public class RedisIdWorker {

    /**
     * Custom epoch: 2022-01-01 00:00:00 UTC.
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * The low 32 bits store the Redis auto-increment sequence.
     */
    private static final int COUNT_BITS = 32;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();

        // 1. Generate timestamp part.
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. Generate sequence part with Redis INCR. Split by date to avoid unlimited growth.
        String date = now.format(DATE_FORMATTER);
        Long count = stringRedisTemplate.opsForValue().increment(INCR_ID_KEY + keyPrefix + ":" + date);
        if (count == null) {
            throw new IllegalStateException("redis increment result is null");
        }

        // 3. Put timestamp in high bits and sequence in low 32 bits.
        return timestamp << COUNT_BITS | count;
    }
}
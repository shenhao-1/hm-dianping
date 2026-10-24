package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Component
public class CacheClient {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * Cache pass-through: cache empty string for missing database data.
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        R value = dbFallback.apply(id);
        if (value == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        set(key, value, time, unit);
        return value;
    }

    /**
     * Cache breakdown with mutex lock: only one thread rebuilds cache.
     */
    public <R, ID> R queryWithMutex(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type,
                                    Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        String lockKey = lockKeyPrefix + id;
        boolean isLock = false;
        try {
            isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }

            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }

            R value = dbFallback.apply(id);
            if (value == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            set(key, value, time, unit);
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("query cache interrupted", e);
        } finally {
            if (isLock) {
                unlock(lockKey);
            }
        }
    }

    /**
     * Cache breakdown with logical expiration: return old data first, rebuild in background.
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null && StrUtil.isBlank(json)) {
            return null;
        }

        if (json == null) {
            return queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        if (redisData.getExpireTime() == null) {
            return JSONUtil.toBean(json, type);
        }

        R value = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return value;
        }

        String lockKey = lockKeyPrefix + id;
        if (tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R freshValue = dbFallback.apply(id);
                    if (freshValue == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return;
                    }
                    setWithLogicalExpire(key, freshValue, time, unit);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        return value;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
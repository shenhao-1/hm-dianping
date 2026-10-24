package com.hmdp.utils;

public class RedisConstants {
    public static final String INCR_ID_KEY = "icr:";

    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final String LOCK_ORDER_KEY = "order:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String STREAM_ORDERS_KEY = "stream.orders";
    public static final String STREAM_ORDERS_GROUP = "g1";
    public static final String STREAM_ORDERS_CONSUMER = "c1";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FOLLOWS_KEY = "follows:";
    public static final String FOLLOWS_INIT_KEY = "follows:init:";
    public static final String LOCK_FOLLOW_KEY = "lock:follow:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String SHOP_GEO_INIT_KEY = "shop:geo:init:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String UV_KEY = "uv:";
    public static final Long UV_TTL_DAYS = 90L;
}
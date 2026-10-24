package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_INIT_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final double SHOP_SEARCH_RADIUS_METERS = 5000D;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY,
                LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        if (shop == null) {
            return Result.fail("shop not found");
        }
        return Result.ok(shop);
    }

    public void saveShop2Redis(Long id, Long expireTime) {
        Shop shop = getById(id);
        if (shop == null) {
            return;
        }
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + id, shop, expireTime, TimeUnit.SECONDS);
    }

    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        if (shop == null || shop.getTypeId() == null) {
            return Result.fail("shop information is incomplete");
        }
        if (!save(shop)) {
            return Result.fail("failed to save shop");
        }
        addShopToGeo(shop);
        return Result.ok(shop.getId());
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop == null || shop.getId() == null) {
            return Result.fail("shop id cannot be null");
        }

        Shop oldShop = getById(shop.getId());
        if (oldShop == null) {
            return Result.fail("shop not found");
        }
        if (!updateById(shop)) {
            return Result.fail("failed to update shop");
        }

        cacheClient.delete(CACHE_SHOP_KEY + shop.getId());

        // Remove the old point first because the type or coordinates may have changed.
        removeShopFromGeo(oldShop);
        Shop updatedShop = getById(shop.getId());
        addShopToGeo(updatedShop);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (typeId == null) {
            return Result.fail("shop type cannot be null");
        }
        int pageNumber = current == null || current < 1 ? 1 : current;

        // Keep the original database pagination when the client does not send coordinates.
        if (x == null && y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(pageNumber, DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        if (x == null || y == null) {
            return Result.fail("longitude and latitude must be provided together");
        }

        ensureShopGeoInitialized(typeId);

        int from = (pageNumber - 1) * DEFAULT_PAGE_SIZE;
        int end = pageNumber * DEFAULT_PAGE_SIZE;
        String geoKey = SHOP_GEO_KEY + typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults =
                stringRedisTemplate.opsForGeo().radius(
                        geoKey,
                        new Circle(new Point(x, y), new Distance(SHOP_SEARCH_RADIUS_METERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );

        if (geoResults == null || geoResults.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = geoResults.getContent();
        List<Long> shopIds = new ArrayList<>(content.size() - from);
        Map<Long, Double> distanceById = new java.util.LinkedHashMap<>();

        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : content.subList(from, content.size())) {
            Long shopId = Long.valueOf(result.getContent().getName());
            shopIds.add(shopId);
            if (result.getDistance() != null) {
                distanceById.put(shopId, result.getDistance().getValue());
            }
        }

        Map<Long, Shop> shopsById = listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, shop -> shop));
        List<Shop> shops = new ArrayList<>(shopIds.size());
        for (Long shopId : shopIds) {
            Shop shop = shopsById.get(shopId);
            if (shop != null) {
                shop.setDistance(distanceById.get(shopId));
                shops.add(shop);
            }
        }
        return Result.ok(shops);
    }

    /**
     * Lazily loads all shops of a type into Redis GEO once.
     */
    private void ensureShopGeoInitialized(Integer typeId) {
        String initKey = SHOP_GEO_INIT_KEY + typeId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(initKey))) {
            return;
        }

        List<Shop> shops = query()
                .eq("type_id", typeId)
                .list();
        for (Shop shop : shops) {
            addShopToGeo(shop);
        }
        stringRedisTemplate.opsForValue().set(initKey, "1");
    }

    private void addShopToGeo(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null
                || shop.getX() == null || shop.getY() == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().add(
                SHOP_GEO_KEY + shop.getTypeId(),
                new Point(shop.getX(), shop.getY()),
                shop.getId().toString()
        );
    }

    private void removeShopFromGeo(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null) {
            return;
        }
        stringRedisTemplate.opsForGeo().remove(
                SHOP_GEO_KEY + shop.getTypeId(),
                shop.getId().toString()
        );
    }
}
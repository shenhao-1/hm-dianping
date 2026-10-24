package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

/**
 * Shop service.
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result saveShop(Shop shop);

    Result update(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
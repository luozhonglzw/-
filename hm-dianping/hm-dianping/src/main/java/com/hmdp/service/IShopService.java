package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {


    /**
     * 通过id找数据
     * @param id
     * @return
     */
    Result queryById(Long id);


    /**
     * 修改数据时 删除缓存
     * @param shop
     * @return
     */
    Result update(Shop shop);

    Result queryShopByTpye(Integer typeId, Integer current, Double x, Double y);
}

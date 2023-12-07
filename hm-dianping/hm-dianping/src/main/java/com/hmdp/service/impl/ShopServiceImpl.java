package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    /**
     * 根据Id找
     *
     * @param id
     * @return
     */

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        /**
         * 处理缓存失效 对数据库大量攻击
         */
        //缓存穿透 即有解决null
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //互斥锁 解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //逻辑过期 解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //7.返回
        return Result.ok(shop);
    }

    /**
     * 缓存加入互斥锁代码
     *
     * @param id
     * @return
     */
    //新建一个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商店缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在 不为空格的话
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在 直接返回
            return null;
        }

        //4. 命中 把json反序列化 转为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //(JSONObject) redisData.getData() 这里获得的实际上是json数据
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1 未过期 直接返回店铺信息
            return shop;
        }

        //5.2 已过期 需要缓存重建

        //6. 缓存重建

        //6.1 获得互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLck(lockKey);
        //6.2 判断是否获取锁成功
        if (isLock) {
            //6.3 成功 开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建 缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //6.4 失败 返回过期的店铺信息 直接返回的就是过期的店铺信息
        return shop;
    }

    /**
     * 缓存加入互斥锁代码
     *
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商店缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在 不为空格的话
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在 直接返回
//            return Result.ok(shop);
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //这里为空格 不为空的值
        if (shopJson != null) {
//            return Result.fail("店铺信息不存在");
            return null;
        }
        //4.实现缓存重建
        //4.1 获得互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLck(lockKey);
            //4.2 判断是否获取成功
            if (!isLock) {
                //4.3 获取失败 休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 获得成功 根据id查询数据库
            shop = getById(id);
            //5.不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

                //返回错误的信息
//            return Result.fail("店铺不存在");
                return null;
            }

            //6.存在 写入 redis 并设置过期时间 缓存雪崩解决一: 在TTL后加入随机数 即不让同时失效
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                    Long.parseLong(CACHE_SHOP_TTL + RandomUtil.randomNumbers(2)), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7. 释放互斥锁
            unLock(lockKey);
        }
        //8.返回
//        return Result.ok(shop);
        return shop;
    }

    /**
     * 缓存穿透代码
     *
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商店缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在 不为空格的话
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
            return shop;
        }
        //这里为空格 不为空的值
        if (shopJson != null) {
//            return Result.fail("店铺信息不存在");
            return null;
        }
        //4.不存在 根据id查询数据库
        Shop shop = getById(id);

        //5.不存在，返回错误
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            //返回错误的信息
//            return Result.fail("店铺不存在");
            return null;
        }

        //6.存在 写入 redis 并设置过期时间 缓存雪崩解决一: 在TTL后加入随机数 即不让同时失效
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),
                Long.parseLong(CACHE_SHOP_TTL + RandomUtil.randomNumbers(2)), TimeUnit.MINUTES);
        //7.返回
//        return Result.ok(shop);
        return shop;
    }


    public void saveShop2Redis(Long id, Long expireSeconds) {
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    /**
     * 获得锁 只有开始第一个获取该值的是1 其他或者以后获取的都是0 setIfAbsent 获取并判断
     *
     * @param key
     * @return
     */
    private boolean tryLck(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,
                "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }


    /**
     * 释放锁
     *
     * @param key
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


    /**
     * 修改数据时 删除缓存 先修改数据库 再删缓存
     *
     * @param shop
     * @return
     */
    @Override
    @Transactional//这里要注释 保证事务的一致性
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public Result queryShopByTpye(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要坐标查询
        if (x == null || y == null) {
            // 不需要 坐标查询 按数据库查询
            Page<Shop> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            //返回数据
            return Result.ok(page.getRecords());
        }

        //2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;


        //3. 查询redis 按照距离排序 分页 结果 shopId.distance//GEO
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =stringRedisTemplate.opsForGeo().search(
                        key,//指定key
                        GeoReference.fromCoordinate(x, y),//指定x,y
                        new Distance(5000),// 指定距离五公里内
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //4.解析id 查询shop
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            //没有下一页 结束
            return Result.ok(Collections.emptyList());
        }
        //4.1截取 from到end 原来是0到end
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            //4.2获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //4.3 获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });

        //5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop: shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }
}

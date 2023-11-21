package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


@Slf4j
@Component
public class CacheClient {


    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate=stringRedisTemplate;
    }

    /**
     * 将Java序列化为json 并存储在key中 并设置TTL过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }


    /**
     * 将Java序列化为json 并存储在key中 并设置逻辑过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }


    /**
     * 根据key查询 并反序列为指定类型 用缓存空值 解决缓存穿透问题
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,
    Long time,TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商店缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在 不为空格的话
        if (StrUtil.isNotBlank(json)) {
            //3.存在 直接返回
            return JSONUtil.toBean(json, type);
//            return Result.ok(shop);
        }
        //这里为空格 不为空的值
        if (json != null) {
//            return Result.fail("店铺信息不存在");
            return null;
        }
        //4.不存在 根据id查询数据库
        R r = dbFallback.apply(id);

        //5.不存在，返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            //返回错误的信息
//            return Result.fail("店铺不存在");
            return null;
        }

        //6.存在 写入 redis 并设置过期时间 缓存雪崩解决一: 在TTL后加入随机数 即不让同时失效
        this.set(key,r,time,unit);
        //7.返回
//        return Result.ok(shop);
        return r;
    }


    /**
     * 根据key查询 并反序列为指定类型 利用逻辑过期解决缓存击穿问题
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID ,R> dbFallback,Long time,TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商店缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在 不为空格的话
        if (StrUtil.isBlank(json)) {
            //3.不存在 直接返回
            return null;
        }

        //4. 命中 把json反序列化 转为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //(JSONObject) redisData.getData() 这里获得的实际上是json数据
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期 直接返回店铺信息
            return r;
        }

        //5.2 已过期 需要缓存重建

        //6. 缓存重建

        //6.1 获得互斥锁
        String lockKey= LOCK_SHOP_KEY+id;
        boolean isLock = tryLck(lockKey);
        //6.2 判断是否获取锁成功
        if(isLock){
            //6.3 成功 开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    //重建缓存

                    //查询数据库
                    R r1=dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }
                finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4 失败 返回过期的店铺信息 直接返回的就是过期的店铺信息
        return r;
    }

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

}

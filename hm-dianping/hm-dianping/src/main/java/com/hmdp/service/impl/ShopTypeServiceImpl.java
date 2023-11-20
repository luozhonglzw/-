package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LIST;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    /**
     * 用redis查询shoplist
     * @return
     */


    @Resource
    private ShopTypeMapper shopTypeMapper;

    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        String key=CACHE_SHOP_LIST+"list";
        //1.查询redis
        String map = stringRedisTemplate.opsForValue().get(key);
        log.info(1+"----------"+map);
        //2.判断redis中是否有数据
        if(map!=null){//不为空 空为true
            //3.redis中有数据直接返回
            return Result.ok(map);

        }
        //4.redis中没有数据查询数据库
        List<ShopType> shopType = typeService.query().orderByAsc("sort").list();
//        List<ShopType> shopType = shopTypeMapper.selectList(null);
        log.info(2+"------------"+shopType.toString());
        //5.判断查询数据库中是否有数据
        if(shopType.isEmpty()){
            //6.没有数据 报错
            return Result.fail("店铺类型不存在");
        }

        //7.有数据 写入redis
        stringRedisTemplate.opsForValue().set(key, shopType.toString());
        //8. 返回
        return Result.ok(shopType);
    }
}

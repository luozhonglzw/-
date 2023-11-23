package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        /**
         * 判断 订单是否开始与是否结束 是否库存不足
         */
        //1.查优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("秒杀未开始");
        }

        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //未开始
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        //对同一个user用户加锁 加以区别 只锁同一个用户 即一个人一单 这里toString new 一个对象 不行 找值
        Long userId = UserHolder.getUser().getId();
        /**
         * 先获取锁 提交事务 再释放锁 避免事务未提交就释放锁 只适用于单机 当多机 同一个用户仍然报错 即不同的jvm就会有不同的锁监视器
         * 锁只是锁线程 当有多个jvm高并发的时候 就会使出现”锁不住的现象“
         * 统一一把锁 即多个jvm 分布式锁 公用一把锁
         * 分布式锁： 多进程可见 互斥 高可用 高并发/高性能 安全性
         * set key(lock) value(thread1) ex 10 (过期时间) nx(不存在才可以set) 使ex nx 变成原子性操作
         */
//        synchronized (userId.toString().intern()) {
        /**
         * 这里事务不会生效 没有事务功能
         * 拿到事务代理的对象
         * 加入依赖 代理模式
         * 加入注解 暴露代理对象
         */
        //获取代理对象（事务）
        //创建锁对象
        SimpleRedisLock Lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);

        //获取锁
        boolean islock = Lock.tryLock(5);
        //判断是否获取锁成功
        if(!islock){
            //获取失败 返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try{
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            //释放锁
            Lock.unLock();
        }

//            }
    }


    /**
     * 这里加乐观锁 对数据库操作 并创建订单
     *
     * @param voucherId
     * @return
     */
    @Transactional
    public Result createVoucherOrder(Long voucherId) {

        //6.1 一人一单 用用户的id查询订单 同
        Long userId = UserHolder.getUser().getId();


        //6.1 查询订单
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();//查询数据库中 订单id与voucherId都存在的订单的数量
        //6.2 判断是否存在
        if (count > 0) {
            return Result.fail("用户已经购买过了");
        }

        //5.扣除库存

        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")//set stock=stock-1
                .eq("voucher_id", voucherId)//where id=?
//                .eq("stock",voucher.getStock())//where and stock =?
                .gt("stock", 0)//and stock >0
                .update();
        //乐观锁 当库存与查询的库存一致时才可以修改数据
        if (!success) {
            //扣除失败
            return Result.fail("库存不足");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2 用户id
        voucherOrder.setUserId(userId);
        //6.3 代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //返回订单id
        return Result.ok(orderId);

    }
}

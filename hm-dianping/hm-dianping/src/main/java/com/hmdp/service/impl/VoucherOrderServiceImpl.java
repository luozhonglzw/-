package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.config.RedissonConfig;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    /**
     * 将代码 分两步
     * 1.利用redis完成库存余量 一人一单的判断 完成抢单的业务
     * 2.将下单业务放入阻塞队列中 利用独立线程异步下单
     *
     * 问题
     * 内存限制
     * 可能数据不一致 因为放在内存中
     * 解决
     * stream
     */
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    //定义lua脚本 即用来使redis的操作具有原子性
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    //初始化脚本 寻找脚本的文件 设置返回值类型  静态代码块 初始化的时候 直接加载完成
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private static final ExecutorService  seckill_order_executor = Executors.newSingleThreadExecutor();

    //在spring类初始化的时候 新建一个线程池 提交线程任务

    @PostConstruct
    private void init(){
        seckill_order_executor.submit(new VoucherOrderHandler());
    }

    /**
     * 放置队列后 异步下单
     * 用阻塞队列 与线程池
     * 不断取出订单并创建
     */
    //线程任务 执行任务的时候 类初始化完成之后
    //异步下单 线程池 线程任务
    //获取一个单线程池

    private class  VoucherOrderHandler implements Runnable{


        String queueName="stream.orders";

        @Override
        public void run() {
            while (true){
                try{
                    //1.获取消息队列中的订单消息 XREADGROUP GROUP g1 c1 count 1 block 2000 stream stream.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),//创建分组 组名和
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),//设置读取的个数和要不要阻塞
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())//创建队列的名字 大于号 即读最新消息
                    );//即count为未知的数据
                    //2. 判断信息获取是否成功 判断list是否为空
                    if(list==null||list.isEmpty()){
                        //2.1 如果获取失败，说明没有消息，继续下一个循环
                        continue;
                    }
                    //3.解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);//count是1 只有一个
                    Map<Object, Object> value = record.getValue();//这个获取存进去的key与value
                    //将获取的map对象转为实体类
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3. 如果获取成功 可以下单
                    handleVoucherOrder(voucherOrder);
                    //4. ack确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                    //创建订单
                }catch (Exception e){
                    log.error("处理订单异常",e);
                    handlePendingLits();
                }
            }
        }

        private void handlePendingLits() {
            while (true){
                try{
                    //qu
                    //1.获取pending-list中的订单消息 XREADGROUP GROUP g1 c1 count 1 stream stream.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),//创建分组 组名和
                            StreamReadOptions.empty().count(1),//设置读取的个数
                            StreamOffset.create(queueName, ReadOffset.from("0"))//创建队列的名字 大于号 即读最新消息
                    );//即count为未知的数据
                    //2. 判断信息获取是否成功 判断list是否为空
                    if(list==null||list.isEmpty()){
                        //2.1 如果获取失败，说明pending-list没有消息，即也没有待取的消息 直接跳出循环 去执行上面的
                        break;
                    }
                    //3.解析消息中的订单消息
                    MapRecord<String, Object, Object> record = list.get(0);//count是1 只有一个
                    Map<Object, Object> value = record.getValue();//这个获取存进去的key与value
                    //将获取的map对象转为实体类
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3. 如果获取成功 可以下单
                    handleVoucherOrder(voucherOrder);
                    //4. ack确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                    //创建订单
                }catch (Exception e){
                    //抛出异常继续读pending-list 直到读完异常为止 直接break
                    log.error("处理pending-list异常",e);
                }
            }

        }
    }
    //阻塞队列 如果没有队列 队列获取数据 如果没有就阻塞
//    private BlockingQueue<VoucherOrder> orderTasks  = new ArrayBlockingQueue<>(1024* 1024);
//    private class  VoucherOrderHandler implements Runnable{
//
//
//        @Override
//        public void run() {
//            while (true){
//                try{
//                    //获取队列中的订单信息 没有数据会阻塞 有数据会取数据
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                }catch (Exception e){
//                    log.error("处理订单异常",e);
//                }
//
//            }
//        }
//    }


    /**
     * 创建订单
     * 加锁保证 并发安全
     * @param voucherOrder
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {

        /**
         * 这是一个全新的线程 获取不到userId
         */
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
//        boolean islock = lock.tryLock(1200);//重试的等待时间 最大等待时间 时间单位
        boolean islock = lock.tryLock();//重试的等待时间 最大等待时间 时间单位
        //4.判断是否获取锁成功
        if(!islock){
            //获取失败 返回错误或重试
            log.error("不允许重复下单");
            return ;
        }
        try{

            //锁拿到后 创建订单
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            //释放锁
            lock.unlock();
        }

//            }
    }

    private IVoucherOrderService proxy;

    /**
     * 创建订单
     * 判断有没有购买资格 库存是否充足
     * 没有直接结束
     * 如果有 将订单 放置队列中
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户的id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");

        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //2.判断结果是否为0
        int r = result.intValue();
        if (r!=0){
            //2.1 不为0 代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //2.2 为0 有购买的资格 保存下单的信息到阻塞队列中



        //已经有资格了 准备加入到阻塞队列中
//        //6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //6.1 订单id
//        voucherOrder.setId(orderId);
//        //6.2 用户id
//        voucherOrder.setUserId(userId);
//        //6.3 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //添加到阻塞队列中
//        orderTasks.add(voucherOrder);


        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //放到队列中
        //3. 返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //获取用户的id
//        Long userId = UserHolder.getUser().getId();
//        //1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//        //2.判断结果是否为0
//        int r = result.intValue();
//        if (r!=0){
//            //2.1 不为0 代表没有购买资格
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
//        //2.2 为0 有购买的资格 保存下单的信息到阻塞队列中
//
//        //已经有资格了 准备加入到阻塞队列中
//        //6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //6.1 订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //6.2 用户id
//        voucherOrder.setUserId(userId);
//        //6.3 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //添加到阻塞队列中
//        orderTasks.add(voucherOrder);
//
//
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //放到队列中
//        //3. 返回订单id
//        return Result.ok(orderId);
//    }
//

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        /**
//         * 判断 订单是否开始与是否结束 是否库存不足
//         */
//        //1.查优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("秒杀未开始");
//        }
//
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("秒杀已经结束");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        //对同一个user用户加锁 加以区别 只锁同一个用户 即一个人一单 这里toString new 一个对象 不行 找值
//        Long userId = UserHolder.getUser().getId();
//        /**
//         * 先获取锁 提交事务 再释放锁 避免事务未提交就释放锁 只适用于单机 当多机 同一个用户仍然报错 即不同的jvm就会有不同的锁监视器
//         * 锁只是锁线程 当有多个jvm高并发的时候 就会使出现”锁不住的现象“
//         * 统一一把锁 即多个jvm 分布式锁 公用一把锁
//         * 分布式锁： 多进程可见 互斥 高可用 高并发/高性能 安全性
//         * set key(lock) value(thread1) ex 10 (过期时间) nx(不存在才可以set) 使ex nx 变成原子性操作
//         */
////        synchronized (userId.toString().intern()) {
//        /**
//         * 这里事务不会生效 没有事务功能
//         * 拿到事务代理的对象
//         * 加入依赖 代理模式
//         * 加入注解 暴露代理对象
//         */
//        //获取代理对象（事务）
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //获取锁
////        boolean islock = lock.tryLock(1200);//重试的等待时间 最大等待时间 时间单位
//        boolean islock = lock.tryLock();//重试的等待时间 最大等待时间 时间单位
//        //判断是否获取锁成功
//        if(!islock){
//            //获取失败 返回错误或重试
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            //释放锁
//            lock.unlock();
//        }
//
////            }
//    }


    /**
     * 这里加乐观锁 对数据库操作 并创建订单
     * 创建订单
     *
     * @param
     * @return
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        //6.1 一人一单 用用户的id查询订单 同
//        Long userId = UserHolder.getUser().getId();

        Long userId = voucherOrder.getUserId();

        //6.1 查询订单
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder)
                .count();//查询数据库中 订单id与voucherId都存在的订单的数量
        //6.2 判断是否存在
        if (count > 0) {
            log.error("用户购买一次了");
            return;
        }

        //5.扣除库存

        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")//set stock=stock-1
                .eq("voucher_id", voucherOrder)//where id=?
//                .eq("stock",voucher.getStock())//where and stock =?
                .gt("stock", 0)//and stock >0
                .update();
        //乐观锁 当库存与查询的库存一致时才可以修改数据
        if (!success) {
            //扣除失败
            log.error("库存不足");
            return;
        }
//        //6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //6.1 订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //6.2 用户id
//        voucherOrder.setUserId(userId);
//        //6.3 代金券id
//        voucherOrder.setVoucherId(voucherId);

        //创建订单
        save(voucherOrder);
//        //返回订单id
//        return Result.ok(orderId);

    }
}

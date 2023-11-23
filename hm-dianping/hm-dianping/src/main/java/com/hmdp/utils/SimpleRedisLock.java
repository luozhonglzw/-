package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{


    private StringRedisTemplate stringRedisTemplate;

    private String name;//业务的名称 锁的名称

    private static final String KEY_PREFIX ="lock:";//给锁加统一的前缀

    //添加UUID 给线程唯一一个标识
    private static final String ID_PREFIX = UUID.fastUUID().toString(true)+"-";

    //定义lua脚本 即用来使redis的操作具有原子性
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    //初始化脚本 寻找脚本的文件 设置返回值类型  静态代码块 初始化的时候 直接加载完成
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 不同的业务 不同的锁
     * @param timeoutSec 锁持有的时间 过期自动删除
     * 加入线程标识 避免 不同的线程导致错误
     *                   Lua脚本 可以编写多条redis命令 确保命令的原子性
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程唯一的标识
        String id =ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX+name, id, timeoutSec, TimeUnit.SECONDS);//如果缺席才运行
        return Boolean.TRUE.equals(success);//自动拆箱有null风险
    }

    @Override
    public void unLock() {
        //调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,//脚本
                Collections.singletonList(KEY_PREFIX+name),//生成单一key的list集合
                ID_PREFIX + Thread.currentThread().getId());//判断key取的值与该值
    }
//    @Override//失败的原因是因为 事务没有原子性 即判断锁与释放锁不是同时完成的
//    public void unLock() {
//        //获取线程id标识
//        String id =ID_PREFIX + Thread.currentThread().getId();
//        //获得锁中的标识
//        String Id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断标识是否一致
//        if(Id.equals(id)){
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//    }
}

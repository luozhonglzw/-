package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final long BEGIN_TIMESTAMP=1640995200L; //开始的时间戳

    private static final int COUNT_BITS=32; //序列号



    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now=LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp =nowSecond-BEGIN_TIMESTAMP;
        //2.生成序列号

        //2.1获取当前的日期 精确到天 以每一天为一个时间戳 即可以统计时间 又可以防止总量的数据过多

        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        //2.2 自增长 redis自带的自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3.拼接并返回

        //向左移动32位

        return timestamp<<COUNT_BITS|count;
    }


}

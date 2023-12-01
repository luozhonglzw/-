package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置 新建一个配置类
        Config config=new Config();
        //创建 RedissonClient 对象
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        return Redisson.create(config);

    }
}

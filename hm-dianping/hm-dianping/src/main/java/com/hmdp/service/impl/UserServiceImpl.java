package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {



    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送验证码与保存验证码 保存到session中
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合 放回错误信息
            return Result.fail("手机号格式错误");
        }


        //3.符合 生成6位随机验证码
        String code = RandomUtil.randomNumbers(6);

        //4.保存验证码到 redis 加入有效期 set key value ex 120
//        session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送验证码成功，验证码：{}",code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        
        String phone = loginForm.getPhone();
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        
        //2.校验验证码
        //这个是网络中的code 获取验证码
//        Object cacheCode = session.getAttribute("code");

        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        //这个是前端传输的code 这里是不等于
        String code = loginForm.getCode();
        if(cacheCode==null||!cacheCode.equals(code)){
            //3.不一致 报错
            return Result.fail("验证码错误");
        }
        
        //4.一致 查询用户 query自动去查询数据库 按一列一列的查询 得到的数据类返回
        User user = query().eq("phone", phone).one();

        //5.判断用户存不存在
        
        if(user==null){
            //6.不存在 创建用户 并保存在 redis
            user=createUserWithPhone(phone);
        }

        //7.存在 保存用户的信息到 redis

        //7.1 随机生成token 作为登录令牌
        String token = UUID.randomUUID().toString(true);

        //7.2 将User对象 转为hashMap 存储在redis中 这里会报错 即 不允许 hash必须要是String 自定义一个复制的规矩
        UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)//忽略空的值 修改字段值
                        .setFieldValueEditor((fieldName,fieldValue)-> fieldValue.toString()));

        //7.3 存储
        String tokenKey= LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        // 7.4 30分钟清空
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //8. 返回token
//        session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        //2.保存用户
        save(user);
        return user;
    }
}
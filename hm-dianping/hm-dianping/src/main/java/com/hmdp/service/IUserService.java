package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {


    /**
     * 发送验证码并保存验证码 保存到session
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);


    /**
     * 短信验证码登录与注册
     * 根据手机号查询用户 不存在创建新用户 并保存用户到session
     * 存在登录 并用户到保存session
     * @param loginForm
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);
}

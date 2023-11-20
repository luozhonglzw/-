package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 拦截器 用于拦截session请求 在拦截后保存线程到ThreadLocal中（每个tomcat都是一个特殊的线程 有特殊的id）
     * 这不是spring构造的 只能用构造函数注入
     * 这个是进入controller前校验
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */


//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获得session 请求头中的token
////        HttpSession session = request.getSession();
//
//        String token = request.getHeader("authorization");
//
//        //为空拦截 说明未登录
//        if(StrUtil.isBlank(token)){
//            //4.不存在 拦截 将返回的结果设置状态码返回
//            response.setStatus(401);
//            return false;
//        }
//        //2.获得session中的用户 通过token获得redis用户 这里 通过entries 获取一个map
////        Object user = session.getAttribute("user");
//        String key=RedisConstants.LOGIN_USER_KEY + token;
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
//        //3.判断用户是否存在
//        if (userMap.isEmpty()){
//            //4.不存在 拦截 将返回的结果设置状态码返回
//            response.setStatus(401);
//            return false;
//        }
//        //将hash数据转为UserDTO,用map填充bean 是否忽略转换中的错误
//
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        //5.存在 保存信息到ThreadLocal
//        UserHolder.saveUser(userDTO);
//
//        //刷新有效期
//        stringRedisTemplate.expire(key,RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);

        //6.放行
        //1.判断是否拦截 （ThreadLocal 中是否有用户）
        if(UserHolder.getUser()==null){
            //没有用户 拦截 设置状态码
            response.setStatus(401);
            //拦截
            return  false;
        }
        //有就放行
        return true;
    }


//    /**
//     * 校验后要做的事
//     * @param request
//     * @param response
//     * @param handler
//     * @param ex
//     * @throws Exception
//     */
//    @Override
//    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        //移除用户
//        UserHolder.removeUser();
//    }
}

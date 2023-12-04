package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //关注或者取关
        //1.判断关注与取关
        Long userId = UserHolder.getUser().getId();
        String key = "follows:"+userId;
        if(isFollow){
            //2.关注 新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                //保存成功 将数据加入到set集合中 可以求交集 key是userId value是 关注的userId
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //3.取关 删除数据 delete from tb_follow where userId=? and follow-user_id=?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            //取关 将redis中的关注id 移除
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());

            }
        }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //判断是否关注

        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();

        //3. 判断


        return Result.ok(count>0);
    }

    @Override
    public Result followCommon(Long id) {

        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();

        //2.获取key
        String key="follows:"+userId;
        String key2="follows:"+id;
        //3.求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect==null||intersect.isEmpty()){
            //无交集
            return Result.ok(Collections.emptyList());
        }
        //4.解析id 转为long
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //5.查询ids
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }


}

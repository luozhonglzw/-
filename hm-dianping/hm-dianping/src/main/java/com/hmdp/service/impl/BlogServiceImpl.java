package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
                // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {

        //1.查询blog
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("博客不存在");
        }
        //2.查询blog有关的用户
        queryBlogUser(blog);
        //3. 查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //用户未登录
            return;
        }
        Long userId = user.getId();
        String key=BLOG_LIKED_KEY+blog.getId();
        //2.判断当前登录用户是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        //已经点赞了
        blog.setIsLike(score!=null);
    }


    /**
     * 这里的id是blog 博文的id
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {

        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //博文id加入key中 锁定博文
        String key=BLOG_LIKED_KEY+id;
        //2.判断当前登录用户是否点赞 如果点赞了 会有一个分数 分数为空的话 说明没有点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3.如果未点赞 允许点赞
        if(score==null){
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //3.2 保存用户到redis中的sortedset集合中 zadd key value score 以时间戳为分数
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }
        else {
            //4.如果已点赞 取消点赞
            //4.1 数据库点赞数 -1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if(isSuccess){
                //4.2 把用户从redis的set集合中移除
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key=BLOG_LIKED_KEY+id;
        //1.查询top5的点赞用户 zrange key 0 5
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty()){
            //处理空异常
            return Result.ok(Collections.emptyList());
        }
        //2.解析出其中的用户id 把数据映射map转为list 将tops的值转为long 并将其collectors收集起来
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        //查询不按

        //自己拼接字符串
        String idstr = StrUtil.join(",",ids);
        // 将user转为userDTo where id in (5,1) ORDER BY FIELD(id,5,1)  这里要自己排序
        List<UserDTO> users = userService.query()
                .in("id",id)
                .last("ORDER BY FIELD(id,"+idstr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //3.根据id 查询用户
        return Result.ok(users);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}

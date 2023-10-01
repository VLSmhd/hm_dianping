package com.vls.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.vls.dto.Result;
import com.vls.dto.ScrollResult;
import com.vls.dto.UserDTO;
import com.vls.entity.Blog;
import com.vls.entity.Follow;
import com.vls.entity.User;
import com.vls.mapper.BlogMapper;
import com.vls.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vls.service.IFollowService;
import com.vls.service.IUserService;
import com.vls.utils.RedisConstants;
import com.vls.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;
    @Override
    public Result queryBlogById(Long id) {
        //查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        //查询用户
        queryBlogUser(blog);

        //查询blog是否被点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private  void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
    }

    @Override
     public void isBlogLiked(Blog blog) {
        Long id = blog.getId();
        //获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            //用户未登录，无需查询
            return ;
        }
        Long userId = user.getId();
        //判断用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score  = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //判断用户是否点赞
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score  = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //没有点赞，更新数据库,更新到redis
        if(score == null){//防止isMember为空
            boolean isSuccess = this.update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());//三个参数，key  value  分数
            }
        }else{
            //已点赞，取消点赞，更新数据库，删除redis
            boolean isSuccess = this.update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }


    @Override
    public Result queryBlogLikes(Long id) {
        //查询Top5点赞范围 zrange key 0 4
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //获取用户id
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String ids = StrUtil.join(",", userIds);
        List<User> users = userService.query().in("id", userIds).last("order by field(id,"+ ids + ")").list();

        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toList());
        //根据用户id查询用户返回userDto
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //保存数据到数据库
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(userId);
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("上传笔记失败");
        }
        //保存到redis中对应用户的收件箱。  收件箱：sortedSet
            //查询所有粉丝
            //当前用户就是up，所以要根据userId查follow_user_id表
        List<Follow> fans = followService.query().eq("follow_user_id", userId).list();
            //发到粉丝收件箱
        for (Follow fan : fans) {
            //每个粉丝都有个对应的收件箱。
            Long fanId = fan.getUserId();
            String key = RedisConstants.FEED_FANS_KEY + fanId;
            //按照时间戳排序
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询收件箱
     * @param max        相当于lastId
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        UserDTO currentUser = UserHolder.getUser();
        //查询收件箱   ZREVRANGEBYSCORE KEY MAX min withscores limit offset count
        String key = RedisConstants.FEED_FANS_KEY + currentUser.getId();
        //获取分页查询的结果
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }

        //解析数据,需要返回给前端的数据：blogId   minTime（这次查询的最小时间戳）, offset(以上次查询的与score最小值相同的元素的个数，作为偏移量，防止重复数据）
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        long minTime = 0L;
        //[min,max]是闭区间，所以高低也得跳过一个。
        int offsetNum = 1;
        //循环每一个结果，目的是得到上次查询的最小时间戳minTime和偏移量。
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String blogId = typedTuple.getValue();
            blogIds.add(Long.valueOf(blogId));
            long timeStamp = typedTuple.getScore().longValue();
            if(minTime == timeStamp){
                offsetNum++;
            }else{
                offsetNum = 1;
                minTime = timeStamp;
            }
        }
        //再次确认
        offsetNum = minTime != max ? offsetNum : offsetNum + offset;
        String idStr = StrUtil.join(",", blogIds);
        //根据id查blog，自定义排序
        List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + idStr + ")").list();
        //封装 返回
        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }

        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(offsetNum);
        r.setMinTime(minTime);

        return Result.ok(r);
    }



}

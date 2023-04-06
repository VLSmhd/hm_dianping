package com.vls.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.vls.dto.Result;
import com.vls.dto.UserDTO;
import com.vls.entity.Follow;
import com.vls.entity.User;
import com.vls.mapper.FollowMapper;
import com.vls.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vls.service.IUserService;
import com.vls.utils.RedisConstants;
import com.vls.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FOLLOW_USER_KEY + userId;
        //判断这个操作是关注还是取关
        if(isFollow) {
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followId);
            boolean isSuccess = save(follow);


            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key, followId.toString());
            }
        }else {
            boolean isSuccess = remove(new QueryWrapper<Follow>().
                    eq("user_id", userId).eq("follow_user_id", followId));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key, followId.toString());
            }

        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("follow_user_id", followId).
                eq("user_id", userId).eq("follow_user_id", followId).count();


        return Result.ok(count > 0);
    }



    @Override
    public Result followCommons(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        String key1 = RedisConstants.FOLLOW_USER_KEY + userId;
        String key2 = RedisConstants.FOLLOW_USER_KEY + id;
        //当前用户的关注列表与查看的某人的关注列表求交集
        Set<String> commons = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(commons == null ||commons.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> commonIds = commons.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = userService.listByIds(commonIds);
        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}

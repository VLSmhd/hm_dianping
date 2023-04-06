package com.vls.service;

import com.vls.dto.Result;
import com.vls.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followId, Boolean isFollow);

    Result isFollow(Long followId);

    Result followCommons(Long id);
}

package com.vls.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.vls.dto.Result;
import com.vls.entity.ShopType;
import com.vls.mapper.ShopTypeMapper;
import com.vls.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vls.utils.RedisConstants;
import org.springframework.data.redis.connection.ConnectionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        if(shopTypeJson!= null && CollUtil.isNotEmpty(shopTypeJson)){
            List<ShopType> shopTypes = new ArrayList<>();
            for(String s : shopTypeJson){
                shopTypes.add(JSONUtil.toBean(s, ShopType.class));
            }
            return Result.ok(shopTypes);
        }

        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if(shopTypes == null || CollUtil.isEmpty(shopTypes)){
            return Result.fail("shop类型不存在");
        }
        for (ShopType shopType : shopTypes) {
            shopTypeJson.add(JSONUtil.toJsonStr(shopType));
        }

        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY, shopTypeJson);
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_TYPE_KEY, 30,TimeUnit.MINUTES);
        return Result.ok(shopTypes);
    }
}

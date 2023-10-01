---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by LEGION.
--- DateTime: 2023/3/20 19:50
---

--优惠券id
local voucherId = ARGV[1]
--用户id
local userId = ARGV[2]
--订单id
--local orderId = ARGV[3]

--库存key
local stockKey = 'seckill:stock:' .. voucherId

--订单key
local orderKey = 'seckill:order:' .. voucherId


--判断库存是否充足
if(tonumber(redis.call('get', stockKey)) <= 0)then
    return 1
end

--判断用户是否下单  SISMEMBER命令
if(redis.call('sismember', orderKey, userId) == 1)then
    return 2
end

--扣库存 incrby
redis.call('incrby', stockKey, -1)

--保存用户 (虚拟下单)
redis.call('sadd', orderKey, userId)

----发送消息到队列里  XADD stream.orders * k1 v1 k2 v2 ...
--redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)

return 0
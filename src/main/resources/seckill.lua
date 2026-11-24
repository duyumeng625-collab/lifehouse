--1.参数列表
--1.1优惠券id
local voucherId = ARGV[1]
--1.2用户id
local userId = ARGV[2]

--2.数据key
--2.1库存key
local stockKey = 'seckill:stock:' .. voucherId
--2.2订单key
local orderKey = 'seckill:order:' .. voucherId

--3.脚本业务
--3.1判断库存是否充足
if (tonumber(redis.call('get',stockKey)) <= 0) then
    --库存不足
    return 1
end
--3.2判断用户是否下单 userId是订单的value中的一个
if (redis.call('sismember',orderKey,userId) == 1) then
    --用户已下过单
    return 2
end
--3.3扣库存
redis.call('incrby',stockKey,-1)
--3.4 下单（保存用户id到order集合中）
redis.call('sadd',orderKey,userId)
return 0



-- 座位回滚：锁座超时释放、取消订单、退票共用
-- KEYS[1] 座位图  KEYS[2] 锁座归属 Hash  KEYS[3] 用户限额 Hash  KEYS[4] 流水 Hash  KEYS[5] 场次余票
-- ARGV[1] userId  ARGV[2] orderId  ARGV[3] traceId  ARGV[4] 是否移除用户额度(0/1)  ARGV[5] ttlSeconds  ARGV[6] 座位数量  ARGV[7] 座位编码CSV  ARGV[8..] 座位索引
local seatMapKey = KEYS[1]
local lockHashKey = KEYS[2]
local userKey = KEYS[3]
local traceKey = KEYS[4]
local stockKey = KEYS[5]
local userId = ARGV[1]
local orderId = ARGV[2]
local traceId = ARGV[3]
local removeUserRecord = tonumber(ARGV[4])
local ttlSeconds = tonumber(ARGV[5])
local seatCount = tonumber(ARGV[6])
local seatCodes = ARGV[7]

local writeArgs = {'bitfield', seatMapKey}
for i = 8, 7 + seatCount do
    local index = tonumber(ARGV[i])
    table.insert(writeArgs, 'set')
    table.insert(writeArgs, 'u2')
    table.insert(writeArgs, index * 2)
    table.insert(writeArgs, 0)
end
redis.call(unpack(writeArgs))

for i = 8, 7 + seatCount do
    redis.call('hdel', lockHashKey, tonumber(ARGV[i]))
end

if removeUserRecord == 1 then
    local current = tonumber(redis.call('hget', userKey, userId) or '0')
    local after = current - seatCount
    if after <= 0 then
        redis.call('hdel', userKey, userId)
    else
        redis.call('hset', userKey, userId, after)
    end
end

local beforeQty = tonumber(redis.call('get', stockKey) or '0')
local afterQty = beforeQty + seatCount
redis.call('set', stockKey, afterQty)

local logEntry = cjson.encode({
    logType = 1,
    ts = redis.call('time')[1],
    orderId = orderId,
    traceId = traceId,
    userId = userId,
    seatCodes = seatCodes,
    beforeQty = beforeQty,
    changeQty = seatCount,
    afterQty = afterQty
})
redis.call('hset', traceKey, traceId, logEntry)
if ttlSeconds and ttlSeconds > 0 then
    redis.call('expire', traceKey, ttlSeconds)
end

return 0

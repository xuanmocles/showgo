-- 下单确认：座位由「锁定」转为「已售」，并扣减场次余票、落座位变更流水
-- KEYS[1] 座位图  KEYS[2] 锁座归属 Hash  KEYS[3] 锁座令牌Key  KEYS[4] 流水 Hash  KEYS[5] 场次余票
-- ARGV[1] orderId  ARGV[2] userId  ARGV[3] traceId  ARGV[4] ttlSeconds  ARGV[5] 座位数量  ARGV[6] 座位编码CSV
-- ARGV[7..6+n] 座位索引  ARGV[7+n] lockToken
-- 注意：锁座令牌 Key 的「值」保存的是座位索引列表，令牌本身在 Key 名字里，
--      因此归属校验要用调用方传入的 lockToken（ARGV 最后一个），不能用 GET 出来的值。
local seatMapKey = KEYS[1]
local lockHashKey = KEYS[2]
local lockTokenKey = KEYS[3]
local traceKey = KEYS[4]
local stockKey = KEYS[5]
local orderId = ARGV[1]
local userId = ARGV[2]
local traceId = ARGV[3]
local ttlSeconds = tonumber(ARGV[4])
local seatCount = tonumber(ARGV[5])
local seatCodes = ARGV[6]

-- 令牌 Key 不存在说明锁座已过期或被消费
if not redis.call('get', lockTokenKey) then
    return string.format('{"code": %d}', 10105)
end
local lockToken = ARGV[7 + seatCount]

-- 二次校验：锁定必须属于本次请求，避免并发下抢占他人的锁座
local writeArgs = {'bitfield', seatMapKey}
for i = 7, 6 + seatCount do
    local index = tonumber(ARGV[i])
    local owner = redis.call('hget', lockHashKey, index)
    if owner ~= lockToken then
        return string.format('{"code": %d}', 10106)
    end
    table.insert(writeArgs, 'set')
    table.insert(writeArgs, 'u2')
    table.insert(writeArgs, index * 2)
    table.insert(writeArgs, 2)
end
redis.call(unpack(writeArgs))

for i = 7, 6 + seatCount do
    redis.call('hdel', lockHashKey, tonumber(ARGV[i]))
end

local beforeQty = tonumber(redis.call('get', stockKey) or '0')
local afterQty = beforeQty - seatCount
if afterQty < 0 then
    afterQty = 0
end
redis.call('set', stockKey, afterQty)

local logEntry = cjson.encode({
    logType = -1,
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

redis.call('del', lockTokenKey)

return string.format(
    '{"code": %d, "beforeQty": %d, "changeQty": %d, "afterQty": %d}',
    0, beforeQty, seatCount, afterQty)

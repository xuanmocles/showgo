-- 锁座：校验并原子锁定同一分区同一排的连续座位
-- KEYS[1] 座位图(BitField)  KEYS[2] 锁座归属 Hash  KEYS[3] 锁座令牌  KEYS[4] 用户限额 Hash
-- ARGV[1] userId  ARGV[2] lockToken  ARGV[3] ttlSeconds  ARGV[4] 座位数量  ARGV[5] 单用户限购  ARGV[6..] 座位索引
local seatMapKey = KEYS[1]
local lockHashKey = KEYS[2]
local lockTokenKey = KEYS[3]
local userKey = KEYS[4]
local userId = ARGV[1]
local lockToken = ARGV[2]
local ttlSeconds = tonumber(ARGV[3])
local seatCount = tonumber(ARGV[4])
local maxPerUser = tonumber(ARGV[5])

-- 座位图每座位 2bit，高位在前。这里一次性 GET 出整个位图，在 Lua 内解码，
-- 避免为每个座位发起一次 BITFIELD 往返。
local raw = redis.call('get', seatMapKey)
if not raw then
    return 10101
end

local function statusAt(index)
    local bitOffset = index * 2
    local byte = string.byte(raw, math.floor(bitOffset / 8) + 1)
    if not byte then
        return 0
    end
    local shift = 6 - (bitOffset % 8)
    return math.floor(byte / (2 ^ shift)) % 4
end

local prevIndex = nil
for i = 6, 5 + seatCount do
    local index = tonumber(ARGV[i])
    local status = statusAt(index)
    if status == 2 then
        return 10103
    end
    if status == 3 then
        return 10102
    end
    if status ~= 0 then
        return 10103
    end
    -- 座位必须连续
    if prevIndex ~= nil and index ~= prevIndex + 1 then
        return 10104
    end
    prevIndex = index
end

-- 限购校验：同一场次内锁定 + 已购的座位总数不能超过阈值
local current = tonumber(redis.call('hget', userKey, userId) or '0')
if maxPerUser > 0 and current + seatCount > maxPerUser then
    return 10107
end

-- 置为锁定态（1）
local writeArgs = {'bitfield', seatMapKey}
for i = 6, 5 + seatCount do
    local index = tonumber(ARGV[i])
    table.insert(writeArgs, 'set')
    table.insert(writeArgs, 'u2')
    table.insert(writeArgs, index * 2)
    table.insert(writeArgs, 1)
end
redis.call(unpack(writeArgs))

-- 记录每个座位被哪张令牌锁定，便于下单时二次校验
local indexes = {}
for i = 6, 5 + seatCount do
    local index = tonumber(ARGV[i])
    redis.call('hset', lockHashKey, index, lockToken)
    table.insert(indexes, index)
end
redis.call('set', lockTokenKey, table.concat(indexes, ','), 'EX', ttlSeconds)

redis.call('hincrby', userKey, userId, seatCount)
-- 限额计数至少要覆盖锁座有效期，否则锁过期前计数就先失效了
local userTtl = redis.call('ttl', userKey)
if userTtl < 0 or userTtl < ttlSeconds then
    redis.call('expire', userKey, ttlSeconds + 3600)
end

return 0

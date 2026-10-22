-- 座位图初始化 / 重建：按数据库状态重建 Redis BitField 座位图
-- KEYS[1] 座位图
-- ARGV[1] 座位总数
-- ARGV[2..] 「座位索引:状态」对，用于覆盖已售/锁定/不可用座位
local seatMapKey = KEYS[1]
local total = tonumber(ARGV[1])

-- 写入最后一个座位，保证整个位图长度覆盖全场；缺失的位默认可售(0)
redis.call('bitfield', seatMapKey, 'set', 'u2', (total - 1) * 2, 0)

for i = 2, #ARGV do
    local pair = ARGV[i]
    local sep = string.find(pair, ':')
    if sep then
        local index = tonumber(string.sub(pair, 1, sep - 1))
        local status = tonumber(string.sub(pair, sep + 1))
        redis.call('bitfield', seatMapKey, 'set', 'u2', index * 2, status)
    end
end
return 0

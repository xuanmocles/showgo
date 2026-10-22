-- 读取整场座位状态，返回长度为座位总数的字符串，每个字符是一个座位的状态
-- KEYS[1] 座位图  ARGV[1] 座位总数
local raw = redis.call('get', KEYS[1])
local total = tonumber(ARGV[1])
if not raw then
    raw = ''
end

local out = {}
for index = 0, total - 1 do
    local bitOffset = index * 2
    local byte = string.byte(raw, math.floor(bitOffset / 8) + 1) or 0
    local shift = 6 - (bitOffset % 8)
    out[#out + 1] = tostring(math.floor(byte / (2 ^ shift)) % 4)
end
return table.concat(out)

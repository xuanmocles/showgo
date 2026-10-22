-- 连座推荐：在指定座位索引区间内寻找 count 个连续可售座位
-- KEYS[1] 座位图  ARGV[1] 起始索引  ARGV[2] 结束索引  ARGV[3] 需要的连续座位数
local seatMapKey = KEYS[1]
local startIndex = tonumber(ARGV[1])
local endIndex = tonumber(ARGV[2])
local need = tonumber(ARGV[3])

if need <= 0 then
    return ''
end

local raw = redis.call('get', seatMapKey)
if not raw then
    return ''
end

local function isAvailable(index)
    local bitOffset = index * 2
    local byte = string.byte(raw, math.floor(bitOffset / 8) + 1)
    if not byte then
        return false
    end
    local shift = 6 - (bitOffset % 8)
    return math.floor(byte / (2 ^ shift)) % 4 == 0
end

local runStart = -1
local run = 0
for index = startIndex, endIndex do
    if isAvailable(index) then
        if run == 0 then
            runStart = index
        end
        run = run + 1
        if run >= need then
            local result = {}
            for i = runStart, runStart + need - 1 do
                table.insert(result, i)
            end
            return table.concat(result, ',')
        end
    else
        run = 0
    end
end

return ''

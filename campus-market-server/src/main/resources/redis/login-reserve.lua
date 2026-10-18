-- KEYS[1]: 来源IP窗口；KEYS[2]: 同来源同账号窗口。
-- ARGV: IP上限、IP窗口毫秒、账号上限、账号窗口毫秒、本次随机票据。
local ipLimit = tonumber(ARGV[1])
local ipWindow = tonumber(ARGV[2])
local accountLimit = tonumber(ARGV[3])
local accountWindow = tonumber(ARGV[4])
local ipCount = tonumber(redis.call('GET', KEYS[1]) or '0')
if not ipCount or ipCount < 0 then return redis.error_reply('invalid login counter') end
local entries = redis.call('HGETALL', KEYS[2])
local accountCount = #entries / 2
local previousFailures = {}
for i = 1, #entries, 2 do
    if entries[i + 1] == 'failed' then
        table.insert(previousFailures, entries[i])
    elseif entries[i + 1] ~= 'pending' then
        return redis.error_reply('invalid login reservation')
    end
end
local retry = 0
if ipCount >= ipLimit then
    local ttl = redis.call('PTTL', KEYS[1])
    if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ipWindow); ttl = ipWindow end
    retry = math.max(retry, ttl, 1)
end
if accountCount >= accountLimit then
    local ttl = redis.call('PTTL', KEYS[2])
    if ttl < 0 then redis.call('PEXPIRE', KEYS[2], accountWindow); ttl = accountWindow end
    retry = math.max(retry, ttl, 1)
end
if retry > 0 then return 'LIMIT:' .. tostring(retry) end
-- 检查、计数、过期和占位处于同一脚本中；并发请求不会一起越过阈值。
redis.call('INCR', KEYS[1])
if redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], ipWindow) end
redis.call('HSET', KEYS[2], ARGV[5], 'pending')
if redis.call('PTTL', KEYS[2]) < 0 then redis.call('PEXPIRE', KEYS[2], accountWindow) end
return 'OK:' .. table.concat(previousFailures, ',')

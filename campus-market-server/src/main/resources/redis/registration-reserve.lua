-- One source, fixed window beginning at its first admitted request.
-- KEYS[1]: hashed source registration key; ARGV: limit, window milliseconds.
local raw = redis.call('GET', KEYS[1])
if raw then
    if not string.match(raw, '^[1-9][0-9]*$') then return 'ERROR' end
    local count = tonumber(raw)
    local ttl = redis.call('PTTL', KEYS[1])
    if not count or count > 10000000 or ttl < 1 then return 'ERROR' end
    if count >= tonumber(ARGV[1]) then return 'LIMIT:' .. tostring(ttl) end
end
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
return 'OK'

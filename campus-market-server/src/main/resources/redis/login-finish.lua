-- ARGV: 本次票据、success/failure/abort、本次开始前已经失败的票据列表。
-- 窗口到期或实例重启后，旧请求不能修改新窗口中的记录。
if redis.call('HGET', KEYS[1], ARGV[1]) ~= 'pending' then return 'EXPIRED' end
if ARGV[2] == 'failure' then
    redis.call('HSET', KEYS[1], ARGV[1], 'failed')
elseif ARGV[2] == 'success' then
    redis.call('HDEL', KEYS[1], ARGV[1])
    -- 只清理开始校验前已存在的失败，保留并发产生的新失败与其他在途请求。
    for ticket in string.gmatch(ARGV[3], '[^,]+') do
        if redis.call('HGET', KEYS[1], ticket) == 'failed' then
            redis.call('HDEL', KEYS[1], ticket)
        end
    end
elseif ARGV[2] == 'abort' then
    redis.call('HDEL', KEYS[1], ARGV[1])
else
    return redis.error_reply('invalid login outcome')
end
return 'OK'

-- KEYS[1]: seckill stock key
-- KEYS[2]: users who have ordered this voucher
-- KEYS[3]: Redis Stream order queue
-- ARGV[1]: current user id
-- ARGV[2]: voucher id
-- ARGV[3]: order id

local stock = redis.call('GET', KEYS[1])
if (not stock) or (tonumber(stock) <= 0) then
    return 1
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call(
    'XADD', KEYS[3], '*',
    'userId', ARGV[1],
    'voucherId', ARGV[2],
    'id', ARGV[3]
)
return 0
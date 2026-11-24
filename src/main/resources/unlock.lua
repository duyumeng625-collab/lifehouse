--判断当前线程标示与锁的线程标示是否相同
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end

return 0
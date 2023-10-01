

if(redis.call('get', KEYS[1] == ARG[1])) then
    return redis.call('del' , KEYS[1])
end
return 0
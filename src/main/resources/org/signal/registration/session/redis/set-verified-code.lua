local sessionKey = KEYS[1]

local verifiedCode = ARGV[1]

if (redis.call("EXISTS", sessionKey) == 1) then
    redis.call("HSET", sessionKey, "verified-code", verifiedCode)
    return true
else
    return false
end

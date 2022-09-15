local sessionKey = KEYS[1]

local expectedCurrentSession = ARGV[1]
local updatedSession = ARGV[2]

if (redis.call("GET", sessionKey) == expectedCurrentSession) then
    redis.call("SET", sessionKey, updatedSession, "KEEPTTL")
    return true
else
    return false
end

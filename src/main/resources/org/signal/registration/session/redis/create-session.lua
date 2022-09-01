local sessionKey = KEYS[1]

local e164 = ARGV[1]
local sender = ARGV[2]
local sessionData = ARGV[3]
local ttlSeconds = ARGV[4]

redis.call("HSET", sessionKey, "e164", e164, "sender", sender, "session-data", sessionData)
redis.call("EXPIRE", sessionKey, ttlSeconds)

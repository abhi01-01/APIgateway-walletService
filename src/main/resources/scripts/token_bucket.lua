-- Input Parameters
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2]) -- Tokens replenished per second
local requested = tonumber(ARGV[3]) -- Usually 1 token per request

-- 1. Fetch exact server time from Redis (Prevents clock skew across distributed pods)
-- redis.call('TIME') returns an array: { "seconds", "microseconds" }
local redis_time = redis.call('TIME')
local current_time_sec = tonumber(redis_time[1])
local current_time_usec = tonumber(redis_time[2])
local now = current_time_sec + (current_time_usec / 1000000)

-- 2. Fetch current bucket state from Redis Hash
local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

-- 3. Initialize bucket if it does not exist
if tokens == nil then
    tokens = capacity
    last_refill = now
end

-- 4. Calculate Replenishment
local elapsed = math.max(0, now - last_refill)
local replenished = elapsed * rate
tokens = math.min(capacity, tokens + replenished)

-- 5. Evaluate and Mutate State
local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

-- 6. Persist State
redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)

-- 7. Optimize Memory: Set TTL so inactive users don't consume RAM forever
-- TTL = time to refill completely + 5 seconds buffer
local ttl = math.ceil(capacity / rate) + 5
redis.call('EXPIRE', key, ttl)

return allowed
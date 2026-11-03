-- Fixed one-second windows. KEYS[1] = the user's counter for this second, KEYS[2] = the tenant's counter for this
-- second (unused when ARGV[6] is 0), KEYS[3] = the tenant's set of recently seen users (member = user id, score =
-- last request in epoch millis).
-- ARGV: 1 now (ms), 2 active window (ms), 3 per-user limit, 4 tenant limit per active user, 5 user id, 6 has tenant.
-- Returns {outcome, activeUsers, tenantLimit}; outcome 0 = allowed, 1 = the user is over their limit, 2 = the tenant is.
-- A refused request counts toward nothing, so a client that keeps hammering does not eat the others' allowance.
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local userLimit = tonumber(ARGV[3])
local perActiveUser = tonumber(ARGV[4])
local hasTenant = ARGV[6] == '1'

local active = 0
local tenantLimit = 0
if hasTenant then
  redis.call('ZADD', KEYS[3], now, ARGV[5])
  redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - window)
  redis.call('PEXPIRE', KEYS[3], window)
  active = redis.call('ZCARD', KEYS[3])
  tenantLimit = active * perActiveUser
end

local userCount = tonumber(redis.call('GET', KEYS[1]) or '0')
if userCount >= userLimit then
  return {1, active, tenantLimit}
end

if hasTenant then
  local tenantCount = tonumber(redis.call('GET', KEYS[2]) or '0')
  if tenantCount >= tenantLimit then
    return {2, active, tenantLimit}
  end
  redis.call('INCR', KEYS[2])
  redis.call('PEXPIRE', KEYS[2], 2000)
end

redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], 2000)
return {0, active, tenantLimit}

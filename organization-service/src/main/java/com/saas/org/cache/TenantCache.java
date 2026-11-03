package com.saas.org.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis cache for data that is read often and changes rarely (REQ-SCALE-01). Every key carries the tenant id, so
 * one tenant can never be served another's entry. The cache is never the only line of defence: if Redis is down or
 * slow the loader is called instead, and a value that cannot be read back is dropped and reloaded. A value is
 * removed by the code that changes it (or by the event that announces the change), and every entry also expires on
 * its own, so a missed removal heals itself. Nothing is cached when the loader returns {@code null}, so "not a
 * member" and "billing could not be reached" are never remembered. Turned off with {@code saas.cache.enabled=false}.
 */
@Component
public class TenantCache {

    private static final Logger log = LoggerFactory.getLogger(TenantCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;
    private final Duration accessStatusTtl;

    public TenantCache(StringRedisTemplate redis, ObjectMapper objectMapper,
            @Value("${saas.cache.enabled:true}") boolean enabled,
            @Value("${saas.cache.ttl:PT5M}") Duration ttl,
            @Value("${saas.cache.access-status-ttl:PT1M}") Duration accessStatusTtl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = ttl;
        this.accessStatusTtl = accessStatusTtl;
    }

    /** How long theme and role entries live when nothing removes them first. */
    public Duration ttl() {
        return ttl;
    }

    /** Shorter: it decides whether a tenant may write, so a missed removal must not outlive a minute by much. */
    public Duration accessStatusTtl() {
        return accessStatusTtl;
    }

    /** The cached value, or what the loader returns (which is then cached for {@code ttl}); {@code subKey} may be null. */
    public <T> T get(String region, UUID tenantId, String subKey, Class<T> type, Duration ttl, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String key = key(region, tenantId, subKey);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                try {
                    return objectMapper.readValue(cached, type);
                } catch (JsonProcessingException e) {
                    log.warn("cache entry {} could not be read, reloading it", key);
                    redis.delete(key);
                }
            }
        } catch (RuntimeException e) {
            log.warn("cache unavailable for {}, reading the source instead: {}", key, e.toString());
            return loader.get();
        }
        T value = loader.get();
        if (value != null) {
            try {
                redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
            } catch (JsonProcessingException | RuntimeException e) {
                log.warn("cache entry {} could not be stored: {}", key, e.toString());
            }
        }
        return value;
    }

    /** Removes an entry; a failure is logged and the entry's expiry takes care of it. */
    public void evict(String region, UUID tenantId, String subKey) {
        if (!enabled) {
            return;
        }
        String key = key(region, tenantId, subKey);
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.error("cache entry {} could not be removed, it stays until it expires: {}", key, e.toString());
        }
    }

    private static String key(String region, UUID tenantId, String subKey) {
        if (tenantId == null) {
            throw new IllegalArgumentException("a cache entry always belongs to a tenant");
        }
        return "org:cache:" + region + ":" + tenantId + (subKey == null ? "" : ":" + subKey);
    }
}

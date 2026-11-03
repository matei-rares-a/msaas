package com.saas.org.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the cache promises whatever Redis does (REQ-SCALE-01): it is a shortcut, never a dependency. A dead Redis, an
 * entry it cannot read and a loader that finds nothing all end with the source answering, and a value is stored only
 * when there is one.
 */
@Tag("REQ-SCALE-01")
class TenantCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final UUID tenant = UUID.randomUUID();
    private final AtomicInteger loads = new AtomicInteger();
    private TenantCache cache;

    @BeforeEach
    void cache() {
        when(redis.opsForValue()).thenReturn(values);
        cache = new TenantCache(redis, new ObjectMapper(), true, Duration.ofMinutes(5), Duration.ofMinutes(1));
    }

    private String load() {
        loads.incrementAndGet();
        return "from-source";
    }

    @Test
    void aHitIsServedWithoutTouchingTheSource() {
        when(values.get("org:cache:theme:" + tenant)).thenReturn("\"from-redis\"");

        assertThat(cache.get("theme", tenant, null, String.class, Duration.ofMinutes(5), this::load)).isEqualTo("from-redis");
        assertThat(loads).hasValue(0);
    }

    @Test
    void aMissLoadsFromTheSourceAndStoresItWithTheExpiry() {
        assertThat(cache.get("theme", tenant, "u1", String.class, Duration.ofMinutes(5), this::load)).isEqualTo("from-source");

        verify(values).set("org:cache:theme:" + tenant + ":u1", "\"from-source\"", Duration.ofMinutes(5));
    }

    @Test
    void nothingIsStoredWhenTheSourceHasNoValue() {
        assertThat(cache.get("member-access", tenant, "u1", String.class, Duration.ofMinutes(5), () -> null)).isNull();

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void aSourceFailureIsNotHiddenAndNothingIsStored() {
        assertThatThrownBy(() -> cache.get("access-status", tenant, null, String.class, Duration.ofMinutes(1), () -> {
            throw new IllegalStateException("billing is down");
        })).isInstanceOf(IllegalStateException.class);

        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void anUnreadableEntryIsDroppedAndReloaded() {
        when(values.get("org:cache:theme:" + tenant)).thenReturn("{not json");

        assertThat(cache.get("theme", tenant, null, String.class, Duration.ofMinutes(5), this::load)).isEqualTo("from-source");

        verify(redis).delete("org:cache:theme:" + tenant);
        verify(values).set(eq("org:cache:theme:" + tenant), eq("\"from-source\""), eq(Duration.ofMinutes(5)));
    }

    @Test
    void aDeadRedisFallsBackToTheSourceOnReadAndRemovalWithoutFailing() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("theme", tenant, null, String.class, Duration.ofMinutes(5), this::load)).isEqualTo("from-source");
        cache.evict("theme", tenant, null);

        assertThat(loads).hasValue(1);
    }

    @Test
    void aFailedStoreStillReturnsTheValue() {
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThat(cache.get("theme", tenant, null, String.class, Duration.ofMinutes(5), this::load)).isEqualTo("from-source");
    }

    @Test
    void whenTurnedOffRedisIsNeverTouched() {
        TenantCache off = new TenantCache(redis, new ObjectMapper(), false, Duration.ofMinutes(5), Duration.ofMinutes(1));

        assertThat(off.get("theme", tenant, null, String.class, Duration.ofMinutes(5), this::load)).isEqualTo("from-source");
        off.evict("theme", tenant, null);

        verify(redis, never()).delete(anyString());
    }

    @Test
    void everyKeyBelongsToATenantAndNeverToTwo() {
        UUID other = UUID.randomUUID();

        cache.evict("theme", tenant, null);
        cache.evict("theme", other, null);

        verify(redis).delete("org:cache:theme:" + tenant);
        verify(redis).delete("org:cache:theme:" + other);
        assertThatThrownBy(() -> cache.get("theme", null, null, String.class, Duration.ofMinutes(5), this::load))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

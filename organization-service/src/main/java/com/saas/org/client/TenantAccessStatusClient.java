package com.saas.org.client;

import com.saas.org.cache.CacheRegions;
import com.saas.org.cache.TenantCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Synchronous check of a tenant's write access (REQ-BILL-04): billing-service is the source of truth
 * for PAST_DUE/SUSPENDED. Fails open to "ACTIVE" if billing-service is unreachable, so an outage there
 * does not lock out the rest of the platform. The answer is cached in Redis (REQ-SCALE-01) and removed when billing's
 * subscription-changed event arrives; a fail-open answer is never cached.
 */
@Slf4j
@Component
public class TenantAccessStatusClient {

    private final RestClient restClient;
    private final TenantCache cache;

    public TenantAccessStatusClient(RestClient.Builder builder, @Value("${billing-service.base-url}") String baseUrl, TenantCache cache) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.cache = cache;
    }

    public String accessStatus(UUID tenantId) {
        try {
            String status = cache.get(CacheRegions.ACCESS_STATUS, tenantId, null, String.class, cache.accessStatusTtl(), () -> fetch(tenantId));
            return status == null ? "ACTIVE" : status;
        } catch (RestClientException e) {
            log.warn("could not reach billing-service for tenant {} access status, defaulting to ACTIVE", tenantId, e);
            return "ACTIVE";
        }
    }

    private String fetch(UUID tenantId) {
        AccessStatusResponse result = restClient.get()
                .uri("/internal/tenants/{tenantId}/access-status", tenantId)
                .retrieve()
                .body(AccessStatusResponse.class);
        return result == null ? null : result.status();
    }
}

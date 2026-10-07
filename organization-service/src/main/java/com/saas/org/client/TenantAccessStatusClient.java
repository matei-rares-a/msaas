package com.saas.org.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

/**
 * Synchronous check of a tenant's write access (REQ-BILL-04): billing-service is the source of truth
 * for PAST_DUE/SUSPENDED. Fails open to "ACTIVE" if billing-service is unreachable, so an outage there
 * does not lock out the rest of the platform.
 */
@Slf4j
@Component
public class TenantAccessStatusClient {

    private final RestClient restClient;

    public TenantAccessStatusClient(RestClient.Builder builder, @Value("${billing-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public String accessStatus(UUID tenantId) {
        try {
            AccessStatusResponse result = restClient.get()
                    .uri("/internal/tenants/{tenantId}/access-status", tenantId)
                    .retrieve()
                    .body(AccessStatusResponse.class);
            return result == null ? "ACTIVE" : result.status();
        } catch (RestClientException e) {
            log.warn("could not reach billing-service for tenant {} access status, defaulting to ACTIVE", tenantId, e);
            return "ACTIVE";
        }
    }
}

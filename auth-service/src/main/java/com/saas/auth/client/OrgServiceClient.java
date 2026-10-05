package com.saas.auth.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Synchronous call to org for a user's tenants and roles at login time, with no cache (REQ-SCALE-01,
 * REQ-EVT-09): a user removed from a tenant must not get a token for it on the very next call.
 */
@Component
public class OrgServiceClient {

    private final RestClient restClient;

    public OrgServiceClient(RestClient.Builder builder, @Value("${org-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public List<MembershipSummary> findMemberships(UUID userId) {
        MembershipSummary[] result = restClient.get()
                .uri("/internal/users/{userId}/memberships", userId)
                .retrieve()
                .body(MembershipSummary[].class);
        return result == null ? List.of() : List.of(result);
    }
}

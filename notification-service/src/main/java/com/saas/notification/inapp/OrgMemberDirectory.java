package com.saas.notification.inapp;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Asks organization-service's internal members endpoint (not routed by the gateway) who holds a role in a tenant. */
public class OrgMemberDirectory implements MemberDirectory {

    private record MemberIds(List<UUID> userIds) {
    }

    private final RestClient restClient;

    public OrgMemberDirectory(RestClient.Builder builder, String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) timeout.toMillis());
        requestFactory.setReadTimeout((int) timeout.toMillis());
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public List<UUID> memberIdsWithRoles(UUID tenantId, Set<String> roles) {
        try {
            MemberIds found = restClient.get()
                    .uri("/internal/tenants/{tenantId}/members?roles={roles}", tenantId, String.join(",", new TreeSet<>(roles)))
                    .retrieve()
                    .body(MemberIds.class);
            if (found == null || found.userIds() == null) {
                throw new IllegalStateException("organization-service returned no member list for tenant " + tenantId);
            }
            return found.userIds();
        } catch (RestClientException e) {
            throw new IllegalStateException("could not ask organization-service for the members of tenant " + tenantId, e);
        }
    }
}

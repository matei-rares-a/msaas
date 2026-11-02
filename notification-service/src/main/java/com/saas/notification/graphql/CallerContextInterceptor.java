package com.saas.notification.graphql;

import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Hands the identity the gateway derived from the token (X-User-Id, X-Tenant-Id, X-Role - REQ-AUTH-05)
 * to the data fetchers through the GraphQL context. The client's query never carries identity: there is
 * no argument for it, and nothing but these headers is ever read as the caller.
 */
@Component
public class CallerContextInterceptor implements WebGraphQlInterceptor {

    static final String USER_ID = "caller.userId";
    static final String TENANT_ID = "caller.tenantId";
    static final String ROLE = "caller.role";

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        String tenantId = request.getHeaders().getFirst("X-Tenant-Id");
        String role = request.getHeaders().getFirst("X-Role");
        request.configureExecutionInput((input, builder) -> builder.graphQLContext(context -> {
            if (userId != null) {
                context.put(USER_ID, userId);
            }
            if (tenantId != null) {
                context.put(TENANT_ID, tenantId);
            }
            if (role != null) {
                context.put(ROLE, role);
            }
        }).build());
        return chain.next(request);
    }
}

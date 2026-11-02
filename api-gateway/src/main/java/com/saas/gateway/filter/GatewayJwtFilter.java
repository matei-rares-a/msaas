package com.saas.gateway.filter;

import com.saas.shared.security.AccessTokenClaims;
import com.saas.shared.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Validates the per-tenant access token and forwards its claims as headers (REQ-AUTH-04, REQ-AUTH-05). */
@Component
@RequiredArgsConstructor
public class GatewayJwtFilter implements GlobalFilter, Ordered {

    private final JwtService jwtService;

    // Paths that don't require authentication
    private static final List<String> PUBLIC_PATHS = List.of(
            "/auth/login",
            "/auth/register",
            "/auth/forgot-password",
            "/auth/reset-password",
            "/auth/refresh"
    );

    // Routes a user without any tenant may call with an identity token ("METHOD path"): create an
    // organization, accept an invitation, erase their own account.
    private static final Set<String> IDENTITY_ROUTES = Set.of(
            "POST /organizations", "POST /invitations/accept", "DELETE /auth/account");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Allow public paths through
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);
        Optional<AccessTokenClaims> claims = jwtService.parse(token);

        if (claims.isEmpty()) {
            return forwardIdentityOrReject(exchange, chain, token);
        }

        // Mutate request to forward the token's claims as context headers
        AccessTokenClaims c = claims.get();
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header("X-User-Id", c.userId().toString())
                .header("X-Tenant-Id", c.tenantId().toString())
                .header("X-Role", c.role())
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** A tenant-less user (REQ-AUTH-03) holds only an identity token, valid solely on the routes it
     * needs; every client-supplied context header is dropped so none can be spoofed. */
    private Mono<Void> forwardIdentityOrReject(ServerWebExchange exchange, GatewayFilterChain chain, String token) {
        ServerHttpRequest request = exchange.getRequest();
        Optional<UUID> userId = isIdentityRoute(request) ? jwtService.parseIdentity(token) : Optional.empty();
        if (userId.isEmpty()) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> {
                    h.remove("X-Tenant-Id");
                    h.remove("X-Role");
                })
                .header("X-User-Id", userId.get().toString())
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private static boolean isIdentityRoute(ServerHttpRequest request) {
        return IDENTITY_ROUTES.contains(request.getMethod() + " " + request.getURI().getPath());
    }

    @Override
    public int getOrder() {
        return -1;
    }
}


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
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
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

    @Override
    public int getOrder() {
        return -1;
    }
}


package com.saas.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = UUID.randomUUID().toString();

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header("X-Correlation-Id", correlationId))
                .build();
        mutated.getResponse().getHeaders().add("X-Correlation-Id", correlationId);

        log.debug("Request [{}] {} - correlationId={}", correlationId,
                exchange.getRequest().getURI(), correlationId);

        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return -2;
    }
}

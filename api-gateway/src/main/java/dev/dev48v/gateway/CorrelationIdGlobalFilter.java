package dev.dev48v.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

// Day 20 — a GLOBAL filter: a cross-cutting concern implemented ONCE, at the edge, for EVERY route.
//
// This is the payoff of a single front door. Instead of each service inventing its own request-tracing
// scheme, the gateway stamps every inbound request with a correlation id and logs the round trip. Two
// jobs, split across the two halves of a gateway filter:
//
//   PRE  (before chain.filter): read an incoming X-Correlation-Id or mint a fresh one, then MUTATE the
//        request so the id travels downstream — order-service and inventory-service log the same id, so
//        one request can be traced across every hop. (This is the foundation Day 40's distributed
//        tracing builds on.)
//   POST (after the chain completes): now that the matched route and final status are known, log the
//        outcome and echo the id back to the caller so the client can quote it in a bug report.
//
// A route-scoped GatewayFilter only runs for the route it's attached to; a GlobalFilter runs for all of
// them — exactly right for auth, rate limiting, logging and correlation, which must not depend on which
// service the request happens to hit. HIGHEST_PRECEDENCE makes it run first, so the id exists before
// anything else in the chain (including the load-balancer filter that resolves lb://) looks at it.
@Component
public class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);
    static final String CORRELATION_ID = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest incoming = exchange.getRequest();

        // Reuse a caller-supplied id if present (so a trace started upstream continues), else mint one.
        String header = incoming.getHeaders().getFirst(CORRELATION_ID);
        final String correlationId = (header == null || header.isBlank())
                ? UUID.randomUUID().toString()
                : header;

        // PRE: add the id to the forwarded request so every downstream service sees the same trace id.
        ServerWebExchange mutated = exchange.mutate()
                .request(incoming.mutate().header(CORRELATION_ID, correlationId).build())
                .build();
        // Echo it on the response too — set now, while the response is still uncommitted.
        mutated.getResponse().getHeaders().set(CORRELATION_ID, correlationId);

        long startNanos = System.nanoTime();
        log.info("[{}] --> {} {}", correlationId, incoming.getMethod(), incoming.getURI().getRawPath());

        // POST: runs once the downstream response has come back through the chain.
        return chain.filter(mutated).then(Mono.fromRunnable(() -> {
            Route route = mutated.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("[{}] <-- {} via route '{}' ({} ms)",
                    correlationId,
                    mutated.getResponse().getStatusCode(),
                    route != null ? route.getId() : "unmatched",
                    ms);
        }));
    }

    @Override
    public int getOrder() {
        // Run before every other filter so the correlation id is in place for the whole chain.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

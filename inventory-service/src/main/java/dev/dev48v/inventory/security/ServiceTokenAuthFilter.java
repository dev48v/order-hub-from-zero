package dev.dev48v.inventory.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// Day 24 — the SERVICE-TOKEN GATE that guards inventory-service's HTTP surface.
//
// This is inventory-service's half of inter-service authentication: it AUTHENTICATES every caller of
// its API by requiring a shared service token, and rejects anyone who can't present it with 401. The
// caller side lives in order-service (a Feign RequestInterceptor attaches the token) and at the edge
// (the gateway forwards it) — but the ENFORCEMENT is here, because a service must never rely on its
// callers to police themselves.
//
// WHY a servlet filter (not a controller check or an interceptor): auth is a cross-cutting concern that
// must run at the very edge of the request, before Spring MVC routes to any controller — so an
// unauthenticated request is rejected as cheaply as possible and can never reach the stock logic.
// OncePerRequestFilter guarantees exactly one run per request. This mirrors order-service's Day 13
// RateLimitFilter: same edge-filter shape, same RFC-7807 error envelope.
//
// WHAT IT DELIBERATELY DOES NOT GUARD: only /api/* is filtered (see shouldNotFilter). /actuator/health
// stays OPEN — it must, because Day 22's client-side load balancer PROBES /actuator/health directly on
// each instance to decide who is live; if health checks needed the token, a token typo would make every
// instance look "down" and take the service out of rotation. Eureka registration is unaffected too.
public class ServiceTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenAuthFilter.class);

    private final ServiceAuthProperties props;
    private final ObjectMapper objectMapper;

    public ServiceTokenAuthFilter(ServiceAuthProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // Guard ONLY the API surface, and only when auth is enabled. Actuator (health probes from the load
    // balancer, Eureka), error dispatches and anything outside /api/ are none of this gate's business —
    // filtering them would break the very health checks that keep the service in rotation.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.enabled()) {
            return true; // master switch off (local experiments / tests that don't exercise auth)
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String presented = request.getHeader(props.header());

        if (isValid(presented)) {
            // Authenticated caller — let the request proceed to the controller.
            chain.doFilter(request, response);
            return;
        }

        // Missing or wrong token — reject at the edge with 401 and an RFC-7807 body. Log at WARN with
        // the caller's address but NEVER the presented token value (a bad token can still be a secret,
        // and logging credentials is itself a leak).
        String reason = (presented == null || presented.isBlank())
                ? "missing " + props.header() + " header"
                : "invalid service token";
        log.warn("Rejected unauthenticated call to {} from {} — {}",
                request.getRequestURI(), request.getRemoteAddr(), reason);
        writeUnauthorized(response, reason);
    }

    // Constant-time comparison against the configured token. MessageDigest.isEqual does not short-circuit
    // on the first differing byte, so it doesn't leak how much of the token was correct via timing — the
    // standard way to compare secrets. A blank presented value (or a blank configured token) never matches.
    private boolean isValid(String presented) {
        if (!StringUtils.hasText(presented) || !StringUtils.hasText(props.token())) {
            return false;
        }
        byte[] a = presented.getBytes(StandardCharsets.UTF_8);
        byte[] b = props.token().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    // A 401 with a problem+json body matching the RFC-7807 envelope the rest of OrderHub returns, so a
    // caller parses ONE consistent error shape everywhere. Built as a plain map (not ProblemDetail) so it
    // serialises identically with any ObjectMapper — including the bare one a unit test supplies.
    private void writeUnauthorized(HttpServletResponse response, String reason) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Unauthorized");
        problem.put("status", HttpStatus.UNAUTHORIZED.value());
        problem.put("detail", "Service authentication required: " + reason + ".");
        problem.put("timestamp", Instant.now().toString());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problem);
    }
}

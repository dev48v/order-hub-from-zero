package dev.dev48v.inventory.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 24 — externalised knobs for INTER-SERVICE AUTHENTICATION on the inventory service.
//
// Until today, inventory-service trusted anyone who could reach it: any process that knew its
// address (or resolved it through Eureka) could read stock and reserve units. Inside a cluster that
// is a real risk — a compromised pod, a misconfigured job, or a curious developer on the network can
// call a service that assumed it was "internal, therefore safe". Zero-trust says the opposite: a
// service must AUTHENTICATE its callers even inside the perimeter.
//
// The simplest machine-to-machine scheme is a SHARED SERVICE TOKEN: order-service (and the gateway)
// present a secret token on every call; inventory-service checks it and rejects anything without a
// valid one with 401. This record binds the three knobs of that check from `service.auth.*`:
//   • enabled — master switch, so tests / local experiments can turn the gate off.
//   • header  — the header the token travels in (default X-Service-Token).
//   • token   — the EXPECTED value. It is a SECRET, so it is NEVER committed here: application.yml
//               binds it from a ${SERVICE_TOKEN} environment-variable placeholder (Day 23's rule —
//               secrets resolve from the environment, never from git). @DefaultValue keeps a clearly
//               labelled non-secret so the service still boots locally when the var is absent.
//
// Same shape as RateLimitProperties / OrderProperties: one immutable record, relaxed-bound, retunable
// per profile or via env vars (SERVICE_AUTH_ENABLED, SERVICE_AUTH_HEADER, SERVICE_TOKEN) with no recompile.
@ConfigurationProperties(prefix = "service.auth")
public record ServiceAuthProperties(

        // Master switch. Real deployments keep it on; a test that isn't exercising auth can flip it off.
        @DefaultValue("true") boolean enabled,

        // The header the caller must present the token in. Kept configurable so the scheme can align
        // with an existing convention without a code change.
        @DefaultValue("X-Service-Token") String header,

        // The token this service ACCEPTS. Bound from ${SERVICE_TOKEN} in application.yml so the real
        // value comes from the environment; the default here is an obvious non-secret for local dev.
        @DefaultValue("") String token
) {
}

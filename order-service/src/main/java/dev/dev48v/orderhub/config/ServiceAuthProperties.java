package dev.dev48v.orderhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 24 — the CALLER'S half of inter-service authentication.
//
// inventory-service now demands a shared service token on every /api/** call (its ServiceTokenAuthFilter
// returns 401 without one). order-service is that caller, so it must PRESENT the token on every outbound
// Feign request. This record binds the two knobs it needs from `service.auth.*`:
//   • header — the header to put the token in; must match inventory-service's service.auth.header.
//   • token  — the token value to send. It is a SECRET: application.yml binds it from a ${SERVICE_TOKEN}
//              environment-variable placeholder (Day 23's rule), so the real value comes from the
//              environment and never from git. Both services read the SAME ${SERVICE_TOKEN} var, so they
//              agree on the token without either one hardcoding it.
//
// Same immutable-record style as OrderProperties / RateLimitProperties; retunable via env vars
// (SERVICE_AUTH_HEADER, SERVICE_TOKEN) with no recompile. Registered in OrderHubApplication's
// @EnableConfigurationProperties alongside the others.
@ConfigurationProperties(prefix = "service.auth")
public record ServiceAuthProperties(

        // The header the token is sent in — must equal inventory-service's expected header.
        @DefaultValue("X-Service-Token") String header,

        // The shared token, resolved from ${SERVICE_TOKEN} in application.yml (never committed plaintext).
        @DefaultValue("") String token
) {
}

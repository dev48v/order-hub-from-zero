package dev.dev48v.orderhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 34 — externalised switch for Spring Security, bound type-safely from "orderhub.security.*".
// WHY a flag: turning authentication on is a policy decision, and this series' rule is that a new
// production concern must never break what already works. Binding one immutable record (relaxed-bound
// from "orderhub.security.enabled") lets the whole security posture be flipped from application.yml,
// a profile, or an env var (ORDERHUB_SECURITY_ENABLED=true) with no recompile.
//
//   enabled — master switch, DEFAULT FALSE. Off: SecurityConfig installs a permit-all filter chain, so
//             every endpoint and every prior test behaves EXACTLY as before (no 401s, no auth). On: HTTP
//             Basic is required — reads need ROLE_USER, writes need ROLE_ADMIN, and actuator health +
//             the OpenAPI docs stay public. SecurityConfig reads this key via @ConditionalOnProperty to
//             pick which SecurityFilterChain bean is created.
//
// @DefaultValue keeps the app booting with security OFF even if the key is missing entirely.
@ConfigurationProperties(prefix = "orderhub.security")
public record OrderSecurityProperties(
        @DefaultValue("false") boolean enabled
) {
}

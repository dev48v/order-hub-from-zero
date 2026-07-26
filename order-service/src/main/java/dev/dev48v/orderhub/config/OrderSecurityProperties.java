package dev.dev48v.orderhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Day 34 → 35 — externalised switches for Spring Security, bound type-safely from "orderhub.security.*".
// WHY a flag: turning authentication on is a policy decision, and this series' rule is that a new
// production concern must never break what already works. Binding one immutable record lets the whole
// security posture be flipped from application.yml, a profile, or an env var with no recompile.
//
//   enabled — Day 34 master switch, DEFAULT FALSE. Off: SecurityConfig installs a permit-all filter chain,
//             so every endpoint and every prior test behaves EXACTLY as before. On (and jwt.enabled off):
//             HTTP Basic is required — reads need ROLE_USER, writes need ROLE_ADMIN, actuator health + the
//             OpenAPI docs stay public.
//
//   jwt     — Day 35 STATELESS JWT auth (nested, prefix "orderhub.security.jwt"):
//               enabled — when true, JwtSecurityConfig's stateless chain takes over from BOTH Day-34 chains
//                         (they back off): the SAME role rules apply, but authentication is a signed Bearer
//                         token obtained from /auth/login rather than per-request Basic credentials. DEFAULT
//                         FALSE, so the default app and all prior tests are untouched.
//               secret  — the HS256 signing secret. Sourced from ${JWT_SECRET} in application.yml so it is
//                         never committed; must be >= 32 bytes (256 bits) for HS256.
//               expiry  — token lifetime (short by design). DEFAULT 15m.
//
// @DefaultValue keeps the app booting with security OFF even if the keys are missing entirely, and builds
// the nested jwt record with its own defaults when the "jwt" block is absent.
@ConfigurationProperties(prefix = "orderhub.security")
public record OrderSecurityProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue Jwt jwt
) {
    public record Jwt(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("change-me-please-change-me-please-32b") String secret,
            @DefaultValue("15m") Duration expiry
    ) {
    }
}

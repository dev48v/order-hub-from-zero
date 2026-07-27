package dev.dev48v.orderhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Day 34 → 36 — externalised switches for Spring Security, bound type-safely from "orderhub.security.*".
// WHY a flag: turning authentication on is a policy decision, and this series' rule is that a new
// production concern must never break what already works. Binding one immutable record lets the whole
// security posture be flipped from application.yml, a profile, or an env var with no recompile.
//
//   enabled — Day 34 master switch, DEFAULT FALSE. Off: SecurityConfig installs a permit-all filter chain,
//             so every endpoint and every prior test behaves EXACTLY as before. On (and jwt/oauth2 off):
//             HTTP Basic is required — reads need ROLE_USER, writes need ROLE_ADMIN, actuator health + the
//             OpenAPI docs stay public.
//
//   jwt     — Day 35 STATELESS JWT auth, SELF-ISSUED (nested, prefix "orderhub.security.jwt"):
//               enabled — when true, JwtSecurityConfig's stateless chain takes over from the Day-34 chains
//                         (they back off): the SAME role rules apply, but authentication is a signed Bearer
//                         token this app mints at /auth/login and verifies with a SHARED HS256 SECRET.
//                         DEFAULT FALSE, so the default app and all prior tests are untouched.
//               secret  — the HS256 signing secret. Sourced from ${JWT_SECRET} so it is never committed.
//               expiry  — token lifetime (short by design). DEFAULT 15m.
//
//   oauth2  — Day 36 OAuth2 / OIDC RESOURCE SERVER (nested, prefix "orderhub.security.oauth2"). The
//             contrast with Day 35: this app no longer ISSUES tokens — an EXTERNAL OIDC provider does.
//             The app only VERIFIES incoming Bearer tokens against the issuer's ASYMMETRIC RS256 key
//             (public half fetched from its JWKS, or configured directly), plus the issuer and (optionally)
//             audience claims. When enabled, OAuth2ResourceServerConfig's stateless chain owns the app and
//             ALL Day-34/35 chains back off — exactly one filter chain is ever active.
//               enabled         — master switch, DEFAULT FALSE (starter present but dark → prior tests green).
//               issuer          — the trusted "iss" (the OIDC issuer URI). Every token must carry it; a
//                                 mismatch is rejected. Also used to auto-derive the JWKS when the other two
//                                 sources are absent (OIDC discovery at ${issuer}/.well-known/openid-configuration).
//               jwkSetUri       — the issuer's JWKS endpoint (its rotating public keys). If set, the decoder
//                                 fetches keys from here (production default for a live IdP).
//               publicKey       — a single RS256 public key (PEM or bare base64 X.509). An alternative to a
//                                 live JWKS for a fixed key — and how the tests point the decoder at an
//                                 in-test-generated keypair without standing up a mock JWKS server.
//               audience        — optional "aud" the token must contain (e.g. this API's identifier). Blank
//                                 → audience is not validated.
//               authoritiesClaim— the claim carrying the caller's authorities. DEFAULT "roles" so a token
//                                 with roles=[ROLE_USER,ROLE_ADMIN] maps straight onto the Day-34/35 rules.
//               authorityPrefix — prefix prepended to each mapped authority. DEFAULT "" (the roles claim
//                                 already holds ROLE_*). Set "SCOPE_" to map an OAuth2 "scope" claim instead.
//
// @DefaultValue keeps the app booting with security OFF even if the keys are missing entirely, and builds
// each nested record with its own defaults when its block is absent.
@ConfigurationProperties(prefix = "orderhub.security")
public record OrderSecurityProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue Jwt jwt,
        @DefaultValue Oauth2 oauth2
) {
    public record Jwt(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("change-me-please-change-me-please-32b") String secret,
            @DefaultValue("15m") Duration expiry
    ) {
    }

    public record Oauth2(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String issuer,
            @DefaultValue("") String jwkSetUri,
            @DefaultValue("") String publicKey,
            @DefaultValue("") String audience,
            @DefaultValue("roles") String authoritiesClaim,
            @DefaultValue("") String authorityPrefix
    ) {
    }
}

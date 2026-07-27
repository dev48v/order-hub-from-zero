package dev.dev48v.orderhub.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

// Day 36 — OAuth2 / OIDC RESOURCE SERVER (Phase 5 continues). The pivotal contrast with Day 35:
//
//   • Day 35 the app was BOTH the issuer AND the verifier of its tokens. It minted HS256 tokens at
//     /auth/login and verified them with the SAME shared secret (symmetric). Fine for one app, but it
//     couples authentication to the app and every service that trusts the token must know the secret.
//
//   • Day 36 the app is ONLY a resource server. Tokens are issued by an EXTERNAL OIDC provider
//     (Keycloak / Auth0 / Okta / Entra ID). The provider signs each token with its PRIVATE key (RS256,
//     asymmetric); this app verifies the signature with the provider's PUBLIC key — which it fetches from
//     the issuer's JWKS endpoint (a set of public keys, rotated by the IdP) or is given directly. There is
//     NO shared secret and NO /auth/login here: the app never sees a password and cannot mint a token, it
//     only VALIDATES bearer tokens it is handed. That is the standard for microservices — one IdP, many
//     stateless resource servers, each trusting the same issuer.
//
// What "validate" means, precisely, and what each failure yields:
//   • SIGNATURE — recompute/verify against the issuer's RS256 public key. Wrong/rotated key → 401.
//   • ISSUER    — the "iss" claim must equal the configured trusted issuer. Mismatch → 401.
//   • EXPIRY    — the "exp"/"nbf" timestamps must be current (JwtTimestampValidator). Expired → 401.
//   • AUDIENCE  — (optional) the "aud" must contain this API's identifier. Missing → 401.
//   • ROLE      — a JwtAuthenticationConverter maps a claim (here "roles") onto Spring authorities, so the
//                 SAME Day-34/35 rules apply (reads need ROLE_USER, writes need ROLE_ADMIN). Valid token,
//                 wrong role → 403 (authenticated but not authorized).
//
// Gated on orderhub.security.oauth2.enabled: the whole config (decoder, converter, chain) only exists when
// the resource server is switched on, and when it is, SecurityConfig's Day-34 chains AND Day-35's JWT chain
// all back off — exactly one filter chain is ever active. Default OFF ⇒ every prior test is untouched.
// @ConditionalOnWebApplication(SERVLET): a filter chain needs HttpSecurity, which only exists in a servlet
// web context — so non-web tests never try to build it.
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "orderhub.security", name = "oauth2.enabled", havingValue = "true")
@EnableConfigurationProperties(OrderSecurityProperties.class)
public class OAuth2ResourceServerConfig {

    // The JwtDecoder is the heart of a resource server: it turns a raw Bearer string into a verified Jwt or
    // throws. We build a NimbusJwtDecoder from ONE of three key sources (in priority order) and then attach
    // the validator chain (timestamp + issuer + optional audience). @ConditionalOnMissingBean lets a test
    // (or a different profile) supply its own decoder instead.
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(OrderSecurityProperties props) {
        OrderSecurityProperties.Oauth2 o = props.oauth2();

        NimbusJwtDecoder decoder;
        if (StringUtils.hasText(o.publicKey())) {
            // A fixed RS256 public key (PEM or bare base64). No network call — verify against this key alone.
            // This is also how the tests point the decoder at an in-test-generated keypair.
            decoder = NimbusJwtDecoder.withPublicKey(parseRsaPublicKey(o.publicKey()))
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        } else if (StringUtils.hasText(o.jwkSetUri())) {
            // The issuer's JWKS endpoint — the decoder fetches (and caches/rotates) the public keys from here.
            decoder = NimbusJwtDecoder.withJwkSetUri(o.jwkSetUri()).build();
        } else if (StringUtils.hasText(o.issuer())) {
            // OIDC discovery: read ${issuer}/.well-known/openid-configuration to find the JWKS automatically.
            return withValidators((NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(o.issuer()), o);
        } else {
            throw new IllegalStateException(
                    "orderhub.security.oauth2 is enabled but no key source is configured " +
                    "(set one of oauth2.publicKey, oauth2.jwkSetUri or oauth2.issuer).");
        }
        return withValidators(decoder, o);
    }

    // Attach the validators. NimbusJwtDecoder's default only checks timestamps; we REPLACE that with a
    // delegating validator so issuer (and optionally audience) are enforced too — the checks that make a
    // token from the WRONG issuer, or without our audience, fail with 401.
    private JwtDecoder withValidators(NimbusJwtDecoder decoder, OrderSecurityProperties.Oauth2 o) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());                 // exp / nbf still current
        if (StringUtils.hasText(o.issuer())) {
            validators.add(new JwtIssuerValidator(o.issuer()));      // iss == trusted issuer
        }
        if (StringUtils.hasText(o.audience())) {
            validators.add(audienceValidator(o.audience()));         // aud contains our API id
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    // A small custom validator: the token's "aud" list must contain the configured audience.
    private OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        OAuth2Error error = new OAuth2Error(
                "invalid_token", "The required audience '" + audience + "' is missing", null);
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }

    // Map a JWT claim onto Spring Security authorities, so the Day-34/35 role rules (hasRole) apply unchanged.
    // Default: read the "roles" claim (which carries ROLE_USER / ROLE_ADMIN) with an EMPTY prefix, so the
    // values become authorities verbatim. To authorize by OAuth2 SCOPES instead, set authoritiesClaim=scope
    // and authorityPrefix=SCOPE_ — then hasAuthority("SCOPE_orders:write") would gate a write.
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(OrderSecurityProperties props) {
        OrderSecurityProperties.Oauth2 o = props.oauth2();
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(o.authoritiesClaim());   // "roles"
        authorities.setAuthorityPrefix(o.authorityPrefix());         // ""
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    // The resource-server filter chain. Stateless (identity lives entirely in the token), CSRF off (a bearer
    // API has no cookie session), SAME authorization rules as Day 34/35. The .oauth2ResourceServer(...).jwt(...)
    // installs a BearerTokenAuthenticationFilter that reads "Authorization: Bearer <token>", runs the decoder
    // + converter, and populates the SecurityContext. Its defaults already do the right thing on failure:
    // missing/invalid/expired/wrong-issuer token → 401 (BearerTokenAuthenticationEntryPoint); valid token but
    // insufficient role → 403 (BearerTokenAccessDeniedHandler) — no custom handlers needed.
    @Bean
    public SecurityFilterChain oauth2ResourceServerFilterChain(HttpSecurity http,
                                                               JwtDecoder jwtDecoder,
                                                               JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // PUBLIC — liveness/health and the API docs need no token. NOTE: there is no
                        // /auth/login here; tokens come from the external IdP, not from this app.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Same role rules as Day 34/35: READS need ROLE_USER, WRITES need ROLE_ADMIN.
                        .requestMatchers(HttpMethod.GET, "/api/**").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // Validate the incoming Bearer JWT against the external issuer and map its roles claim.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    // Parse an RSA public key from PEM text or a bare base64 X.509 (SubjectPublicKeyInfo) body. Strips any
    // PEM armour and whitespace, base64-decodes the DER, and builds an RSAPublicKey.
    private static RSAPublicKey parseRsaPublicKey(String pem) {
        try {
            String base64 = pem
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid orderhub.security.oauth2.publicKey", e);
        }
    }
}

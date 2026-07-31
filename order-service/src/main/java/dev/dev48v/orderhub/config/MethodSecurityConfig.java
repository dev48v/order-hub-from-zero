package dev.dev48v.orderhub.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

// Day 40 — METHOD-LEVEL SECURITY + a ROLE HIERARCHY (Phase 5 · security, the fourth security layer after the
// Day-34 Basic chain, the Day-35 JWT chain and the Day-36 OAuth2 resource server).
//
// URL security vs METHOD security — the two are complementary, not duplicates:
//   • URL security (SecurityConfig / JwtSecurityConfig / OAuth2ResourceServerConfig) authorizes at the EDGE,
//     by HTTP verb + path in the servlet filter chain: "GET /api/** needs ROLE_USER, POST/PUT/PATCH/DELETE
//     /api/** need ROLE_ADMIN". It is coarse and it only sees the request, not the domain object.
//   • METHOD security authorizes DEEP, on the Spring bean method itself, regardless of how the call arrived
//     (HTTP, a @KafkaListener, the saga, a scheduled job). It can also see the ARGUMENTS and the RETURN VALUE,
//     so it expresses rules URL rules cannot — e.g. "a plain user may only read their OWN orders" (ownership).
//     The @PreAuthorize / @PostAuthorize SpEL on OrderService is enforced HERE, by the advisors this config
//     installs, so the same rule holds no matter which caller reaches the service.
//
// HOW it is turned on: @EnableMethodSecurity registers the method-security AOP advisors (the
// AuthorizationManagerBeforeMethodInterceptor for @PreAuthorize, the …AfterMethodInterceptor for
// @PostAuthorize). Without those advisors, the @PreAuthorize / @PostAuthorize annotations on OrderService are
// completely INERT — Spring never intercepts the calls. That is exactly the gate this series relies on.
//
// THE GATE — same discipline as every prior day. The whole config is @ConditionalOnProperty on
// orderhub.security.method.enabled (DEFAULT FALSE):
//   • flag OFF (default) → this @Configuration is never created, so @EnableMethodSecurity never runs, so NO
//     method-security advisor exists and every @PreAuthorize on OrderService is dormant. OrderService behaves
//     byte-for-byte as it did on Day 39 — which is why all prior tests stay green with the annotations present.
//   • flag ON            → the advisors are installed and the service-layer rules are enforced. It composes on
//     TOP of whichever URL chain is active (Basic / JWT / OAuth2), giving defence in depth: the edge rule AND
//     the method rule must both pass.
//
// THE ROLE HIERARCHY: rather than granting every admin ROLE_USER explicitly (as SecurityConfig's in-memory
// admin does), we declare ADMIN > USER ONCE as a RoleHierarchy. Then hasRole('USER') is satisfied by anyone
// holding ROLE_ADMIN, so an admin implicitly passes every USER-gated read without being handed ROLE_USER. The
// hierarchy is wired into the method-security expression handler below so it governs the @PreAuthorize SpEL.
@Configuration
@ConditionalOnProperty(prefix = "orderhub.security.method", name = "enabled", havingValue = "true")
@EnableMethodSecurity   // prePostEnabled defaults to true → @PreAuthorize / @PostAuthorize are active
public class MethodSecurityConfig {

    // ADMIN > USER, declared once. RoleHierarchyImpl.withDefaultRolePrefix() (Spring Security 6.3) prepends
    // ROLE_ for us, so role("ADMIN").implies("USER") means an authority of ROLE_ADMIN "reaches" ROLE_USER.
    // Consequently a caller with only ROLE_ADMIN satisfies hasRole('USER') too — the admin inherits every user
    // permission without a second granted authority. Exposed as a static @Bean because it feeds the security
    // infrastructure and must be built early, before the beans it configures.
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("USER")
                .build();
    }

    // The expression handler evaluates the SpEL inside @PreAuthorize / @PostAuthorize (hasRole(...),
    // authentication.name, #argument, returnObject, ...). We hand it the RoleHierarchy so its hasRole()
    // consults the ADMIN > USER relationship — otherwise the hierarchy would apply to URL rules but NOT to the
    // method-level SpEL. Also static, for the same early-initialization reason as the hierarchy bean.
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }
}

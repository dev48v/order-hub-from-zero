package dev.dev48v.orderhub.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

// Day 34 — Spring Security basics + password authentication (Phase 5 begins).
//
// Adding spring-boot-starter-security to the classpath means a servlet FILTER CHAIN now sits in front of
// every request. Left to its defaults, Boot would secure the WHOLE app and log a generated password — which
// would break every existing endpoint and test. So security here is a FEATURE FLAG, exactly like the rest of
// the series: two SecurityFilterChain beans, each @ConditionalOnProperty on orderhub.security.enabled.
//
//   • enabled=false (DEFAULT) → openFilterChain: permit every request (CSRF off). Behaviour is byte-for-byte
//     what it was on Day 33 — the reason all 98 prior tests stay green with the security starter present.
//   • enabled=true            → securedFilterChain: HTTP Basic auth with role-based authorization. READS
//     (GET /api/**) need ROLE_USER; WRITES (POST/PUT/PATCH/DELETE /api/**) need ROLE_ADMIN; actuator health
//     and the OpenAPI docs are public (permitAll); everything else needs an authenticated user.
//
// The demo users live in an in-memory UserDetailsService with BCrypt-hashed passwords (never plaintext at
// rest): a plain "user" (ROLE_USER) and an "admin" (ROLE_ADMIN + ROLE_USER, so an admin can read as well as
// write). Defining our own UserDetailsService + PasswordEncoder also switches off Boot's default user.
// @ConditionalOnWebApplication(SERVLET): the filter-chain beans need HttpSecurity, which only exists in a
// servlet web context. Guarding the whole config means a NON-web @SpringBootTest (e.g. the config-refresh
// test that runs with spring.main.web-application-type=none) doesn't try to build a filter chain it can't —
// Spring Boot's own security auto-config backs off in a non-web context for exactly the same reason.
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OrderSecurityProperties.class)
public class SecurityConfig {

    // Demo credentials, exposed as constants so the security test authenticates with the exact same values.
    // In-memory + BCrypt is the day's brief; a real system would source users from a DB / directory instead.
    public static final String USER_USERNAME = "user";
    public static final String USER_PASSWORD = "user-pw";
    public static final String ADMIN_USERNAME = "admin";
    public static final String ADMIN_PASSWORD = "admin-pw";

    // BCrypt — the password hashing function. It is deliberately SLOW and salts every hash, so two users with
    // the same password get different stored hashes and brute-forcing is expensive. Passwords are stored ONLY
    // as these one-way hashes; login re-hashes the presented password and compares. Always defined (even when
    // security is off) so it is available and so Boot's default-user auto-config backs off.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // The user store. Two in-memory users, each with a BCrypt-hashed password. roles("USER") is stored as the
    // authority ROLE_USER (Spring prepends ROLE_), which is what hasRole("USER") checks below. The admin gets
    // BOTH roles so it satisfies the ROLE_USER read rule and the ROLE_ADMIN write rule.
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails user = User.withUsername(USER_USERNAME)
                .password(encoder.encode(USER_PASSWORD))
                .roles("USER")
                .build();
        UserDetails admin = User.withUsername(ADMIN_USERNAME)
                .password(encoder.encode(ADMIN_PASSWORD))
                .roles("ADMIN", "USER")
                .build();
        return new InMemoryUserDetailsManager(user, admin);
    }

    // enabled=true (and JWT off) → the Day-34 REAL chain: HTTP Basic + role-based authorization.
    // Day 35: this chain backs off when orderhub.security.jwt.enabled=true, so the stateless JWT chain
    // (JwtSecurityConfig) is the single active chain then. The condition is an expression because it spans
    // two keys; with jwt.enabled defaulting false, the Day-34 behaviour (enabled=true → Basic) is unchanged.
    @Bean
    @ConditionalOnExpression("'${orderhub.security.enabled:false}' == 'true' and '${orderhub.security.jwt.enabled:false}' == 'false'")
    public SecurityFilterChain securedFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF protection defends browser form/session flows against cross-site POSTs; this is a
                // stateless HTTP-Basic API with no cookie session, so CSRF adds nothing and would (wrongly)
                // 403 a valid POST that carries no token. Disable it — standard for a token/basic API.
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // PUBLIC — liveness/health and the API docs must be reachable without a login.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // READS require ROLE_USER; WRITES require ROLE_ADMIN.
                        .requestMatchers(HttpMethod.GET, "/api/**").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        // Everything else (e.g. the rest of actuator) needs at least an authenticated user.
                        .anyRequest().authenticated())
                // Authenticate with the standard Authorization: Basic header. No credentials → 401 (with a
                // WWW-Authenticate challenge); authenticated but wrong role → 403; right role → the handler runs.
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    // enabled=false (DEFAULT) → the OPEN chain. Permit everything, CSRF off, so the presence of the security
    // starter changes NOTHING about the app's existing behaviour. This is what keeps every prior test green.
    // Day 35: also backs off when jwt.enabled=true (the JWT chain owns the app then), so exactly one chain is
    // ever active. With both keys defaulting false, this is the shipped default — identical to Day 34.
    @Bean
    @ConditionalOnExpression("'${orderhub.security.enabled:false}' == 'false' and '${orderhub.security.jwt.enabled:false}' == 'false'")
    public SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}

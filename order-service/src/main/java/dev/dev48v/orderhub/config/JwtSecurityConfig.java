package dev.dev48v.orderhub.config;

import dev.dev48v.orderhub.jwt.JwtAuthenticationFilter;
import dev.dev48v.orderhub.jwt.JwtService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// Day 35 — the STATELESS JWT filter chain (Phase 5 continues). Day 34 authenticated every request with
// HTTP Basic (username + password re-sent each call). Today swaps that for a signed, self-contained JWT:
// the client logs in ONCE at /auth/login (see AuthController) to get a token, then presents it as
// "Authorization: Bearer <token>" on subsequent calls. No password on every request, and — crucially —
// NO server-side session: SessionCreationPolicy.STATELESS means Spring Security creates and consults no
// HttpSession, so the token alone carries the identity. That scales horizontally (any instance can verify
// any token) and is the standard for APIs.
//
// This is a THIRD SecurityFilterChain, gated on orderhub.security.jwt.enabled — mutually exclusive with
// Day 34's open/secured chains (SecurityConfig's two chains back off when jwt.enabled=true). So the app
// has exactly one active chain at a time: OFF → open (permit all), enabled=true → Basic, jwt.enabled=true
// → this JWT chain. The authorization rules are identical to Day 34 (reads need ROLE_USER, writes need
// ROLE_ADMIN, health + docs public), only the AUTHENTICATION mechanism changed — plus /auth/login is now
// public so a client can obtain a token in the first place.
//
// @ConditionalOnWebApplication(SERVLET): like SecurityConfig, a filter chain needs HttpSecurity, which
// only exists in a servlet web context — so non-web tests never try to build it.
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "orderhub.security", name = "jwt.enabled", havingValue = "true")
public class JwtSecurityConfig {

    @Bean
    public SecurityFilterChain jwtSecurityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                // Token API: no browser session/cookie flow, so CSRF adds nothing and would wrongly 403 a POST.
                .csrf(csrf -> csrf.disable())
                // The core of the day: never create or use an HttpSession — identity lives entirely in the token.
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // PUBLIC — obtaining a token, liveness/health and the API docs need no token.
                        .requestMatchers("/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Same role rules as Day 34: READS need ROLE_USER, WRITES need ROLE_ADMIN.
                        .requestMatchers(HttpMethod.GET, "/api/**").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // No Basic/form login here. A missing or invalid token must yield a clean 401 (not a login
                // redirect): a fixed HttpStatusEntryPoint returns 401 for every unauthenticated protected call.
                // A valid token but wrong role still falls to the default AccessDeniedHandler → 403.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // Read + validate the Bearer token before authorization runs.
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

package dev.dev48v.orderhub.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// Day 35 — the request-side of stateless JWT auth. This OncePerRequestFilter runs once per request,
// BEFORE Spring Security's authorization filter, and turns a valid "Authorization: Bearer <token>"
// header into an authenticated SecurityContext.
//
// Flow:
//   • No Authorization header, or not a Bearer token → do nothing, just continue. The request stays
//     ANONYMOUS; if it targets a protected endpoint the chain's entry point returns 401.
//   • A Bearer token that VERIFIES (good signature, not expired) → build a UsernamePasswordAuthentication
//     from the subject + the "roles" claim and put it in the SecurityContext, so the downstream
//     authorization rules (hasRole("USER")/("ADMIN")) apply exactly as they did under Basic auth on Day 34.
//   • A Bearer token that FAILS to verify (tampered / expired / malformed) → clear the context and
//     continue unauthenticated, so the entry point rejects the protected request with 401.
//
// It is deliberately NOT a Spring @Component: registering an OncePerRequestFilter as a bean makes Boot
// add it to the MAIN servlet chain for every request. Instead JwtSecurityConfig instantiates it and
// slots it into the security filter chain only, so it runs solely where security is configured.
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                Claims claims = jwtService.parse(token);            // verifies signature + expiry, else throws
                String username = claims.getSubject();

                List<?> rawRoles = claims.get(JwtService.ROLES_CLAIM, List.class);
                List<SimpleGrantedAuthority> authorities = rawRoles == null
                        ? List.of()
                        : rawRoles.stream().map(Object::toString).map(SimpleGrantedAuthority::new).toList();

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ex) {
                // Invalid / expired / tampered token → stay unauthenticated. The security chain's
                // entry point turns that into a 401 for any protected endpoint.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}

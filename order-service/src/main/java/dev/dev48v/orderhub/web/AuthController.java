package dev.dev48v.orderhub.web;

import dev.dev48v.orderhub.jwt.JwtService;
import dev.dev48v.orderhub.web.dto.LoginRequest;
import dev.dev48v.orderhub.web.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Day 35 — the token-ISSUING endpoint. POST /auth/login authenticates a username + password against the
// SAME in-memory, BCrypt-hashed users from Day 34 (via the UserDetailsService + PasswordEncoder), and on
// success mints a short-lived signed JWT carrying the user's roles. The client then presents that token as
// "Authorization: Bearer <token>" on every subsequent call instead of re-sending the password.
//
// This is the ONE public credential exchange (permitAll in the JWT chain); everything else requires a
// valid token. On bad credentials we return a bare 401 via ResponseEntity (rather than throwing) so the
// response is a clean 401 and never routes through the @RestControllerAdvice catch-all (which would map a
// generic exception to 500). Gated on orderhub.security.jwt.enabled so it only exists when JWT auth is on.
@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(prefix = "orderhub.security", name = "jwt.enabled", havingValue = "true")
public class AuthController {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserDetailsService userDetailsService,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(req.username());
        } catch (UsernameNotFoundException ex) {
            // Unknown user — do not reveal which half failed; a plain 401 for any bad credential.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // Re-hash the presented password and compare against the stored BCrypt hash (Day 34).
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)   // e.g. ROLE_USER, ROLE_ADMIN
                .toList();
        String token = jwtService.issue(user.getUsername(), roles);
        return ResponseEntity.ok(new TokenResponse(token, "Bearer", roles));
    }
}

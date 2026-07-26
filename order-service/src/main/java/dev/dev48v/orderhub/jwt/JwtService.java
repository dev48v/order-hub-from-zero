package dev.dev48v.orderhub.jwt;

import dev.dev48v.orderhub.config.OrderSecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

// Day 35 — the heart of stateless JWT auth: MINT a signed token and VERIFY one.
//
// A JWT (JSON Web Token) is three base64url parts — header.payload.signature. The header names the
// algorithm (HS256), the payload carries the CLAIMS (who the token is for and what they can do), and
// the signature is an HMAC-SHA256 of "header.payload" keyed by a server-only SECRET. Because the secret
// never leaves the server, anyone can READ the claims but only the server can PRODUCE (or alter) a valid
// signature — so a tampered payload no longer matches its signature and verification fails. That property
// is what makes the token self-contained and stateless: the server needs no session store to trust it,
// just the same secret it signed with.
//
// This bean does exactly two things:
//   • issue(...) — build the token on a successful /auth/login: subject = username, a "roles" claim, an
//     issued-at (iat) and a short-lived expiry (exp), all HS256-signed with the configured secret.
//   • parse(...) — verify an incoming token's signature AND expiry, returning its claims; a bad signature
//     (tampered), a wrong key, an expired exp, or malformed input all throw JwtException so the caller
//     (JwtAuthenticationFilter) can reject the request with 401.
//
// Gated on orderhub.security.jwt.enabled: the bean only exists when JWT auth is switched on, so the
// default app (and every prior test) never instantiates it — keeping Day 34's behaviour byte-for-byte.
@Service
@ConditionalOnProperty(prefix = "orderhub.security", name = "jwt.enabled", havingValue = "true")
public class JwtService {

    // The claim that carries the authenticated user's authorities (e.g. ["ROLE_USER","ROLE_ADMIN"]).
    public static final String ROLES_CLAIM = "roles";

    // The HMAC signing key, derived from the configured secret. Keys.hmacShaKeyFor enforces a minimum
    // 256-bit (32-byte) key for HS256 — a short secret throws at startup rather than shipping a weak token.
    private final SecretKey key;

    // Default token lifetime (short by design — a leaked token is only useful until it expires).
    private final Duration ttl;

    public JwtService(OrderSecurityProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.ttl = props.jwt().expiry();
    }

    // Issue a token with the configured expiry.
    public String issue(String subject, List<String> roles) {
        return issue(subject, roles, ttl);
    }

    // Issue a token with an explicit lifetime (used by tests to craft an already-expired token).
    public String issue(String subject, List<String> roles, Duration lifetime) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(subject)                         // sub — who the token identifies
                .claim(ROLES_CLAIM, roles)                // roles — what they may do
                .issuedAt(Date.from(now))                 // iat
                .expiration(Date.from(now.plus(lifetime)))// exp — short-lived
                .signWith(key)                            // HS256 (inferred from the 256-bit key)
                .compact();
    }

    // Verify signature + expiry and return the claims. Throws JwtException on invalid/expired/tampered
    // tokens (ExpiredJwtException, SignatureException, MalformedJwtException are all subtypes).
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

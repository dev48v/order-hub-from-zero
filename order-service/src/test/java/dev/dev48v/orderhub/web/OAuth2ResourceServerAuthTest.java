package dev.dev48v.orderhub.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.dev48v.orderhub.config.OAuth2ResourceServerConfig;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.service.OrderService;
import dev.dev48v.orderhub.web.dto.CreateOrderRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Day 36 — the OAuth2 / OIDC RESOURCE-SERVER slice test. It boots the MVC layer with the resource server
// turned ON (orderhub.security.oauth2.enabled=true) and imports the real OAuth2ResourceServerConfig (the
// stateless chain + the NimbusJwtDecoder + the JwtAuthenticationConverter). The key difference from Day 35:
// there is NO /auth/login and NO shared secret. An EXTERNAL issuer would mint the tokens; here the TEST
// plays that issuer.
//
// A real IdP is far too heavy for CI, so the test IS the issuer: it generates an RSA keypair in-process,
// publishes only the PUBLIC half to the decoder (via orderhub.security.oauth2.publicKey, wired with
// @DynamicPropertySource), and signs test tokens with the PRIVATE half using Nimbus (RS256). A SECOND,
// untrusted keypair stands in for a token from the wrong signer. We prove the whole day's contract:
//   • token signed by the TRUSTED key, correct issuer + audience, roles=[ROLE_USER] → 200 on a read;
//     roles=[ROLE_ADMIN,ROLE_USER] → 201 on a write.
//   • NO token → 401.
//   • WRONG ISSUER → 401.   • BAD SIGNATURE (untrusted key) → 401.   • EXPIRED → 401.
//   • WRONG AUDIENCE → 401.
//   • valid USER token on an ADMIN-only write → 403.
// Every OTHER test in the suite is untouched: none set oauth2.enabled, so none of these beans exist there —
// Day 35 (jwt.enabled) and the default open chain keep behaving exactly as before.
@WebMvcTest(controllers = OrderController.class)
@Import({OAuth2ResourceServerConfig.class, ApiExceptionHandler.class})
class OAuth2ResourceServerAuthTest {

    // The trusted OIDC issuer this resource server accepts, and the audience it requires.
    private static final String ISSUER = "https://issuer.orderhub.test";
    private static final String AUDIENCE = "orderhub-api";

    // Two RSA keypairs generated once: TRUSTED is the "issuer's" key (its public half is given to the
    // decoder); UNTRUSTED signs a forged token whose signature can never verify against the trusted key.
    private static final KeyPair TRUSTED;
    private static final KeyPair UNTRUSTED;

    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            TRUSTED = kpg.generateKeyPair();
            UNTRUSTED = kpg.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // Enable the resource server and point its decoder at the TRUSTED public key + trusted issuer + audience.
    // Registered dynamically because the keypair is generated at runtime, not known at annotation time.
    @DynamicPropertySource
    static void oauth2Props(DynamicPropertyRegistry registry) {
        registry.add("orderhub.security.oauth2.enabled", () -> "true");
        registry.add("orderhub.security.oauth2.issuer", () -> ISSUER);
        registry.add("orderhub.security.oauth2.audience", () -> AUDIENCE);
        registry.add("orderhub.security.oauth2.publicKey",
                () -> Base64.getEncoder().encodeToString(TRUSTED.getPublic().getEncoded()));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService service;

    private final Order sample =
            Order.rehydrate("order-1", "Ada", "Keyboard", 2, OrderStatus.PLACED, Instant.now());

    private String orderJson() throws Exception {
        return objectMapper.writeValueAsString(new CreateOrderRequest("Ada", "Keyboard", 2));
    }

    // Mint an RS256 test token — the job a real OIDC provider would do. Signs with the given private key,
    // so signing with UNTRUSTED produces a token whose signature the decoder rejects.
    private String token(PrivateKey signingKey, String issuer, String audience,
                         List<String> roles, Duration ttl) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("caller")
                .issuer(issuer)
                .audience(audience)
                .claim("roles", roles)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    // A fully valid token for the given roles: trusted key, correct issuer + audience, 15-minute life.
    private String validToken(List<String> roles) throws Exception {
        return token(TRUSTED.getPrivate(), ISSUER, AUDIENCE, roles, Duration.ofMinutes(15));
    }

    // ---------- a valid token opens a protected endpoint ----------
    @Test
    @DisplayName("valid USER token (trusted key + issuer + audience) on a READ -> 200 OK")
    void validUserTokenReadIs200() throws Exception {
        when(service.getOrder("order-1")).thenReturn(sample);

        mockMvc.perform(get("/api/orders/order-1")
                        .header("Authorization", "Bearer " + validToken(List.of("ROLE_USER"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("valid ADMIN token on a WRITE -> 201 Created")
    void validAdminTokenWriteIs201() throws Exception {
        when(service.placeOrder(eq("Ada"), eq("Keyboard"), anyInt())).thenReturn(sample);

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + validToken(List.of("ROLE_ADMIN", "ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson()))
                .andExpect(status().isCreated());
    }

    // ---------- 401 : missing / wrong issuer / bad signature / expired / wrong audience ----------
    @Test
    @DisplayName("NO token on a protected READ -> 401 Unauthorized")
    void noTokenIs401() throws Exception {
        mockMvc.perform(get("/api/orders/order-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("WRONG ISSUER (trusted key, but iss mismatches) -> 401 Unauthorized")
    void wrongIssuerIs401() throws Exception {
        String wrongIssuer = token(TRUSTED.getPrivate(), "https://evil.example.com",
                AUDIENCE, List.of("ROLE_USER"), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/orders/order-1")
                        .header("Authorization", "Bearer " + wrongIssuer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("BAD SIGNATURE (signed by an untrusted key) -> 401 Unauthorized")
    void badSignatureIs401() throws Exception {
        String forged = token(UNTRUSTED.getPrivate(), ISSUER, AUDIENCE,
                List.of("ROLE_USER"), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/orders/order-1")
                        .header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("EXPIRED token -> 401 Unauthorized")
    void expiredTokenIs401() throws Exception {
        // exp 5 minutes in the past — well beyond JwtTimestampValidator's default 60s clock-skew tolerance.
        String expired = token(TRUSTED.getPrivate(), ISSUER, AUDIENCE,
                List.of("ROLE_USER"), Duration.ofMinutes(-5));

        mockMvc.perform(get("/api/orders/order-1")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("WRONG AUDIENCE (aud not for this API) -> 401 Unauthorized")
    void wrongAudienceIs401() throws Exception {
        String wrongAud = token(TRUSTED.getPrivate(), ISSUER, "some-other-api",
                List.of("ROLE_USER"), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/orders/order-1")
                        .header("Authorization", "Bearer " + wrongAud))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 403 : authenticated but wrong role ----------
    @Test
    @DisplayName("valid USER token on an ADMIN-only WRITE -> 403 Forbidden")
    void userTokenWriteIs403() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + validToken(List.of("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson()))
                .andExpect(status().isForbidden());
    }
}

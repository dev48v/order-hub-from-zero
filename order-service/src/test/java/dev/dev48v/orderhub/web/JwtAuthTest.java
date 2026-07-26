package dev.dev48v.orderhub.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dev48v.orderhub.config.JwtSecurityConfig;
import dev.dev48v.orderhub.config.SecurityConfig;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.jwt.JwtService;
import dev.dev48v.orderhub.service.OrderService;
import dev.dev48v.orderhub.web.dto.CreateOrderRequest;
import dev.dev48v.orderhub.web.dto.LoginRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Day 35 — the stateless-JWT slice test. It boots the MVC layer with JWT auth turned ON
// (orderhub.security.jwt.enabled=true) and a fixed secret, importing the real JwtSecurityConfig (the
// stateless chain + the JwtAuthenticationFilter), SecurityConfig (the BCrypt users), the JwtService and
// AuthController. The OrderService is a @MockBean — this is about authN/authZ, not business logic. We prove
// the whole day's contract:
//   • POST /auth/login with valid credentials returns a signed token (+ tokenType/roles).
//   • a VALID token → 200 on a protected read.
//   • NO token → 401.
//   • a TAMPERED token → 401, an EXPIRED token → 401.
//   • a WRONG-ROLE token (USER) → 403 on an ADMIN-only write, while an ADMIN token → 201.
// The DEFAULT-OFF path (and Day 34's Basic chain) are covered by every OTHER test in the suite — they don't
// set jwt.enabled, so the JWT beans don't even exist there.
@WebMvcTest(controllers = {OrderController.class, AuthController.class},
        properties = {
                "orderhub.security.jwt.enabled=true",
                "orderhub.security.jwt.secret=test-only-jwt-signing-secret-that-is-long-enough-123456",
                "orderhub.security.jwt.expiry=15m"
        })
@Import({SecurityConfig.class, JwtSecurityConfig.class, JwtService.class, ApiExceptionHandler.class})
class JwtAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private OrderService service;

    private final Order sample =
            Order.rehydrate("order-1", "Ada", "Keyboard", 2, OrderStatus.PLACED, Instant.now());

    private String orderJson() throws Exception {
        return objectMapper.writeValueAsString(new CreateOrderRequest("Ada", "Keyboard", 2));
    }

    private String loginJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(new LoginRequest(username, password));
    }

    // ---------- /auth/login issues a token ----------
    @Test
    @DisplayName("POST /auth/login with valid credentials -> 200 and a signed JWT")
    void loginReturnsToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SecurityConfig.ADMIN_USERNAME, SecurityConfig.ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        // the returned token is a real, verifiable JWT for the admin (subject + roles)
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = body.get("token").asText();
        assertThat(jwtService.parse(token).getSubject()).isEqualTo(SecurityConfig.ADMIN_USERNAME);
    }

    @Test
    @DisplayName("POST /auth/login with a wrong password -> 401 Unauthorized")
    void loginWrongPasswordIs401() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(SecurityConfig.USER_USERNAME, "not-the-password")))
                .andExpect(status().isUnauthorized());
    }

    // ---------- a valid token opens a protected endpoint ----------
    @Test
    @DisplayName("valid USER token on a READ -> 200 OK")
    void validTokenReadIs200() throws Exception {
        when(service.getOrder("order-1")).thenReturn(sample);
        String token = jwtService.issue(SecurityConfig.USER_USERNAME, List.of("ROLE_USER"));

        mockMvc.perform(get("/api/orders/order-1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("valid ADMIN token on a WRITE -> 201 Created")
    void validAdminTokenWriteIs201() throws Exception {
        when(service.placeOrder(eq("Ada"), eq("Keyboard"), anyInt())).thenReturn(sample);
        String token = jwtService.issue(SecurityConfig.ADMIN_USERNAME, List.of("ROLE_ADMIN", "ROLE_USER"));

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson()))
                .andExpect(status().isCreated());
    }

    // ---------- 401 : missing / tampered / expired ----------
    @Test
    @DisplayName("NO token on a protected READ -> 401 Unauthorized")
    void noTokenIs401() throws Exception {
        mockMvc.perform(get("/api/orders/order-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("TAMPERED token (signature altered) -> 401 Unauthorized")
    void tamperedTokenIs401() throws Exception {
        String token = jwtService.issue(SecurityConfig.USER_USERNAME, List.of("ROLE_USER"));
        // flip the last character of the signature so it no longer matches the payload
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        mockMvc.perform(get("/api/orders/order-1")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("EXPIRED token -> 401 Unauthorized")
    void expiredTokenIs401() throws Exception {
        // issue a token that expired 10 seconds ago (negative lifetime)
        String expired = jwtService.issue(SecurityConfig.USER_USERNAME, List.of("ROLE_USER"), Duration.ofSeconds(-10));

        mockMvc.perform(get("/api/orders/order-1")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 403 : authenticated but wrong role ----------
    @Test
    @DisplayName("USER token on an ADMIN-only WRITE -> 403 Forbidden")
    void userTokenWriteIs403() throws Exception {
        String token = jwtService.issue(SecurityConfig.USER_USERNAME, List.of("ROLE_USER"));

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson()))
                .andExpect(status().isForbidden());
    }
}

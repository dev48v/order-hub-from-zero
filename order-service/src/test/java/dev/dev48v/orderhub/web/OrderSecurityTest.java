package dev.dev48v.orderhub.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dev48v.orderhub.config.SecurityConfig;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Day 34 — the security slice test. It boots the MVC layer WITH Spring Security turned ON
// (orderhub.security.enabled=true) and imports the real SecurityConfig, so the securedFilterChain,
// the in-memory BCrypt users and the HTTP-Basic entry point are all in play. The OrderService is a
// @MockBean (this is about authN/authZ, not business logic). We prove the whole contract the day
// calls for: 401 unauthenticated, 403 for the wrong role, 200/201 for the right role, and that the
// stored passwords are genuine BCrypt hashes. The DEFAULT-OFF path is covered by every OTHER test in
// the suite (they run with security disabled and are unaffected — that's the point of the flag).
@WebMvcTest(controllers = OrderController.class, properties = "orderhub.security.enabled=true")
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class OrderSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService service;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserDetailsService userDetailsService;

    private final Order sample =
            Order.rehydrate("order-1", "Ada", "Keyboard", 2, OrderStatus.PLACED, Instant.now());

    private String orderJson() throws Exception {
        return objectMapper.writeValueAsString(new CreateOrderRequest("Ada", "Keyboard", 2));
    }

    // ---------- 401 : no credentials ----------
    @Test
    @DisplayName("no credentials on a READ endpoint -> 401 Unauthorized")
    void readWithoutCredentialsIs401() throws Exception {
        mockMvc.perform(get("/api/orders/order-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("no credentials on a WRITE endpoint -> 401 Unauthorized")
    void writeWithoutCredentialsIs401() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("valid username but WRONG password -> 401 Unauthorized (BCrypt mismatch)")
    void wrongPasswordIs401() throws Exception {
        mockMvc.perform(get("/api/orders/order-1")
                        .with(httpBasic(SecurityConfig.USER_USERNAME, "not-the-password")))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 403 : authenticated but wrong role ----------
    @Test
    @DisplayName("USER hitting an ADMIN-only WRITE endpoint -> 403 Forbidden")
    void userWritingIs403() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .with(httpBasic(SecurityConfig.USER_USERNAME, SecurityConfig.USER_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson()))
                .andExpect(status().isForbidden());
    }

    // ---------- 200 / 201 : correct credentials + role ----------
    @Test
    @DisplayName("USER reading -> 200 OK")
    void userReadingIs200() throws Exception {
        when(service.getOrder("order-1")).thenReturn(sample);

        mockMvc.perform(get("/api/orders/order-1")
                        .with(httpBasic(SecurityConfig.USER_USERNAME, SecurityConfig.USER_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADMIN writing -> 201 Created")
    void adminWritingIs201() throws Exception {
        when(service.placeOrder(eq("Ada"), eq("Keyboard"), anyInt())).thenReturn(sample);

        mockMvc.perform(post("/api/orders")
                        .with(httpBasic(SecurityConfig.ADMIN_USERNAME, SecurityConfig.ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("ADMIN reading -> 200 OK (admin also holds ROLE_USER)")
    void adminReadingIs200() throws Exception {
        when(service.getOrder("order-1")).thenReturn(sample);

        mockMvc.perform(get("/api/orders/order-1")
                        .with(httpBasic(SecurityConfig.ADMIN_USERNAME, SecurityConfig.ADMIN_PASSWORD)))
                .andExpect(status().isOk());
    }

    // ---------- public endpoints permit-all ----------
    @Test
    @DisplayName("actuator health is PUBLIC -> not blocked by auth (permitted through the filter chain)")
    void healthIsPublic() throws Exception {
        // The actuator handler isn't part of this web slice, so a PERMITTED request falls through past the
        // security filter to the dispatcher (which has no handler for it here). The point being proven is
        // that the security chain does NOT reject it with 401/403 — it is genuinely public, unlike /api/**.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    // ---------- BCrypt ----------
    @Test
    @DisplayName("stored passwords are salted BCrypt hashes that verify against the raw demo passwords")
    void passwordsAreBcryptHashed() {
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);

        UserDetails admin = userDetailsService.loadUserByUsername(SecurityConfig.ADMIN_USERNAME);

        // stored as a BCrypt hash ($2a/$2b prefix), NOT the plaintext password
        assertThat(admin.getPassword()).startsWith("$2");
        assertThat(admin.getPassword()).isNotEqualTo(SecurityConfig.ADMIN_PASSWORD);

        // the hash verifies the correct password and rejects a wrong one
        assertThat(passwordEncoder.matches(SecurityConfig.ADMIN_PASSWORD, admin.getPassword())).isTrue();
        assertThat(passwordEncoder.matches("wrong-password", admin.getPassword())).isFalse();
    }
}

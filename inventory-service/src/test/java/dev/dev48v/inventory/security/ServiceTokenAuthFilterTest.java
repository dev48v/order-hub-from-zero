package dev.dev48v.inventory.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

// Day 24 — the negative and positive cases of the service-token gate, with NO server and NO Spring
// context. We build the filter directly and drive it with MockHttpServletRequest/Response and a
// MockFilterChain. MockFilterChain records the request it was passed, so "did the request get through
// to the controller?" is simply "is chain.getRequest() non-null?". This is the fast, focused test of
// the auth decision itself — the wiring (URL mapping, order) is FilterRegistrationBean config.
@DisplayName("ServiceTokenAuthFilter: inventory-service requires a valid service token on /api/**")
class ServiceTokenAuthFilterTest {

    private static final String TOKEN = "s3cr3t-service-token";
    private static final String HEADER = "X-Service-Token";

    private final ObjectMapper mapper = new ObjectMapper();

    private ServiceTokenAuthFilter filter(boolean enabled) {
        return new ServiceTokenAuthFilter(new ServiceAuthProperties(enabled, HEADER, TOKEN), mapper);
    }

    private MockHttpServletRequest apiRequest(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/inventory/KEYBOARD-001");
        req.setRequestURI("/api/inventory/KEYBOARD-001");
        if (token != null) {
            req.addHeader(HEADER, token);
        }
        return req;
    }

    @Test
    @DisplayName("a request carrying the correct token passes through to the chain")
    void validTokenPasses() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(true).doFilter(apiRequest(TOKEN), res, chain);

        // The chain ran (request forwarded to the controller) and nothing was rejected.
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a request with NO token is rejected with 401 and never reaches the chain")
    void missingTokenIsRejected() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(true).doFilter(apiRequest(null), res, chain);

        assertThat(chain.getRequest()).isNull();                 // short-circuited before the controller
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentType()).contains("application/problem+json");

        JsonNode body = mapper.readTree(res.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(body.get("detail").asText()).contains("missing " + HEADER);
    }

    @Test
    @DisplayName("a request with the WRONG token is rejected with 401")
    void wrongTokenIsRejected() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(true).doFilter(apiRequest("not-the-real-token"), res, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(res.getStatus()).isEqualTo(401);

        JsonNode body = mapper.readTree(res.getContentAsString());
        assertThat(body.get("detail").asText()).contains("invalid service token");
        // The rejected token value must never be echoed back in the response body.
        assertThat(res.getContentAsString()).doesNotContain("not-the-real-token");
    }

    @Test
    @DisplayName("/actuator/health is NOT guarded — health probes work without a token")
    void actuatorHealthIsNotGuarded() throws Exception {
        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
        health.setRequestURI("/actuator/health");   // no token header at all
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(true).doFilter(health, res, chain);

        // shouldNotFilter() is true for non-/api paths, so the chain proceeds untouched.
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("with the master switch off, even an untokened /api call passes (the gate is disabled)")
    void disabledSwitchLetsEverythingThrough() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(false).doFilter(apiRequest(null), res, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }
}

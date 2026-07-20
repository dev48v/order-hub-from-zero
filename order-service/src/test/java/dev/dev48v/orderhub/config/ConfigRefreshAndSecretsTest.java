package dev.dev48v.orderhub.config;

import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.cloud.endpoint.RefreshEndpoint;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// Day 23 — proves the two Day-23 features with a REAL application context but WITHOUT a live server,
// config server, database container or Redis:
//   1. Live refresh — a @RefreshScope bean is re-created when ContextRefresher.refresh() runs (exactly
//      what POST /actuator/refresh triggers), while a plain singleton with the same @Value fields is NOT.
//   2. Secret externalization — the shipping API key resolves from the ${SHIPPING_API_KEY} placeholder,
//      is never a committed real secret, and is only ever surfaced masked.
//
// WHY it boots cleanly offline: webEnvironment=NONE (no Tomcat needed), Eureka/discovery disabled (no
// registry), the config server import is `optional:` so it's skipped when unreachable, the datasource
// defaults to in-memory H2 with Flyway, Redis is lazy (the rate-limiter falls back to in-memory), and the
// Feign client is a @MockBean so no service name ever needs resolving. So `mvn test` runs it with no Docker.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                // Day 28 — this boots the full app context, which now includes the saga's two @KafkaListeners.
                // Keep them from starting against a broker that isn't running in this offline test.
                "orderhub.saga.enabled=false"
        })
@DisplayName("Day 23 · live config refresh + externalized secret")
class ConfigRefreshAndSecretsTest {

    // Replace the load-balanced Feign proxy so the context needs no discovery/registry to build.
    @MockBean
    private InventoryServiceClient inventoryServiceClient;

    @Autowired
    private RefreshableOrderConfig refreshable;

    @Autowired
    private StaticOrderConfig staticConfig;

    @Autowired
    private ShippingCredentials shippingCredentials;

    @Autowired
    private ContextRefresher contextRefresher;

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("the /actuator/refresh machinery (ContextRefresher + RefreshEndpoint) is wired")
    void refreshMachineryIsWired() {
        // ContextRefresher is what /actuator/refresh delegates to; the endpoint bean exists because we
        // added `refresh` to management.endpoints.web.exposure.include.
        assertThat(ctx.getBeansOfType(ContextRefresher.class)).isNotEmpty();
        assertThat(ctx.getBeansOfType(RefreshEndpoint.class)).isNotEmpty();
    }

    @Test
    @DisplayName("a refresh re-creates the @RefreshScope bean but leaves the static bean frozen")
    void refreshRecreatesRefreshScopedBeanOnly() {
        long refreshableBefore = refreshable.getInstanceSeq();
        long staticBefore = staticConfig.getInstanceSeq();

        // Exactly what POST /actuator/refresh does: rebuild the Environment, then dispose refresh-scoped
        // beans so the next access re-creates them. No property has to change for the re-creation to happen.
        contextRefresher.refresh();

        // Touching the beans forces the (lazy) refresh-scoped target to be rebuilt.
        long refreshableAfter = refreshable.getInstanceSeq();
        long staticAfter = staticConfig.getInstanceSeq();

        assertThat(refreshableAfter)
                .as("@RefreshScope bean is re-created on refresh (adopts new config live)")
                .isGreaterThan(refreshableBefore);
        assertThat(staticAfter)
                .as("plain singleton is untouched by refresh (stale until restart)")
                .isEqualTo(staticBefore);
    }

    @Test
    @DisplayName("the shipping secret resolves from the placeholder default and is never a real secret")
    void secretIsExternalizedAndMasked() {
        // No SHIPPING_API_KEY is set in the build, so the safe local placeholder default resolves — and the
        // bean's reported source agrees with the actual environment.
        assertThat(shippingCredentials.fromEnvironment())
                .isEqualTo(System.getenv(ShippingCredentials.ENV_VAR) != null);

        // The committed value is a labelled placeholder, not a real credential.
        assertThat(shippingCredentials.resolvedKey())
                .doesNotContain("sk_live")
                .contains("placeholder");

        // Observability only ever gets the masked fingerprint, never the plaintext.
        String masked = shippingCredentials.maskedKey();
        assertThat(masked).isNotEqualTo(shippingCredentials.resolvedKey());
        assertThat(masked).contains("…");
    }
}

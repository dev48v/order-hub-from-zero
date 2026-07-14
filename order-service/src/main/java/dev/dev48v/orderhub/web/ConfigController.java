package dev.dev48v.orderhub.web;

import dev.dev48v.orderhub.config.RefreshableOrderConfig;
import dev.dev48v.orderhub.config.ShippingCredentials;
import dev.dev48v.orderhub.config.StaticOrderConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

// Day 23 — makes LIVE CONFIG REFRESH and SECRET EXTERNALIZATION observable end to end.
//
// Hit GET /api/config, then in the config repo change app.orders.greeting (or flip
// app.orders.express-checkout-enabled), POST /actuator/refresh, and GET /api/config again:
//   • the "refreshable" block shows the NEW values (its bean was re-created against the new config), and
//     its instanceSeq has ticked up — proof the refresh actually rebuilt it;
//   • the "static" block still shows the OLD values (that bean is frozen until a process restart).
// The "shippingApiKey" block proves the secret is externalized: only a MASKED fingerprint is ever
// returned, alongside where the value was resolved from — never the plaintext, which lives only in an
// environment variable and never in git.
@RestController
@RequestMapping("/api/config")
@Tag(name = "Config", description =
        "Observe Day 23: a @RefreshScope bean that adopts central config changes live via "
                + "/actuator/refresh (vs a static bean that stays stale until restart), and a secret that "
                + "is resolved from an environment-variable placeholder and only ever shown masked.")
public class ConfigController {

    private final RefreshableOrderConfig refreshable;
    private final StaticOrderConfig staticConfig;
    private final ShippingCredentials shippingCredentials;

    public ConfigController(RefreshableOrderConfig refreshable,
                            StaticOrderConfig staticConfig,
                            ShippingCredentials shippingCredentials) {
        this.refreshable = refreshable;
        this.staticConfig = staticConfig;
        this.shippingCredentials = shippingCredentials;
    }

    @GetMapping
    @Operation(summary = "Current config as seen by a refreshable vs a static bean, plus the masked secret")
    @ApiResponse(responseCode = "200", description = "A snapshot; compare before/after POST /actuator/refresh")
    public Map<String, Object> snapshot() {
        Map<String, Object> refreshableView = new LinkedHashMap<>();
        refreshableView.put("greeting", refreshable.getGreeting());
        refreshableView.put("expressCheckoutEnabled", refreshable.isExpressCheckoutEnabled());
        refreshableView.put("instanceSeq", refreshable.getInstanceSeq());
        refreshableView.put("note", "@RefreshScope — re-created on POST /actuator/refresh, adopts new config live");

        Map<String, Object> staticView = new LinkedHashMap<>();
        staticView.put("greeting", staticConfig.getGreeting());
        staticView.put("expressCheckoutEnabled", staticConfig.isExpressCheckoutEnabled());
        staticView.put("instanceSeq", staticConfig.getInstanceSeq());
        staticView.put("note", "plain singleton — frozen at startup values until the process restarts");

        Map<String, Object> secretView = new LinkedHashMap<>();
        secretView.put("masked", shippingCredentials.maskedKey());
        secretView.put("fromEnvironment", shippingCredentials.fromEnvironment());
        secretView.put("source", shippingCredentials.source());
        secretView.put("note", "resolved from a ${SHIPPING_API_KEY} placeholder — plaintext never in git, never returned");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refreshable", refreshableView);
        body.put("staticConfig", staticView);
        body.put("shippingApiKey", secretView);
        body.put("howTo", "Edit the value in the config repo, POST /actuator/refresh, GET /api/config again: "
                + "the refreshable block updates and its instanceSeq increments; the static block does not.");
        return body;
    }
}

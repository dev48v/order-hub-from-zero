package dev.dev48v.orderhub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

// Day 23 — a config-backed bean that picks up CENTRAL config changes LIVE, with no restart.
//
// WHY this exists: a normal singleton binds its @Value fields ONCE, when the bean is created at
// startup. Change app.orders.greeting or flip the express-checkout feature flag in the config repo
// afterwards and this bean would never notice — you'd have to redeploy. @RefreshScope changes that.
// It puts the bean in Spring Cloud's "refresh" scope: instead of a single eager instance, callers get
// a proxy, and the real target is created LAZILY on first use and CACHED. When someone POSTs to
// /actuator/refresh (or fires a Spring Cloud Bus RefreshRemoteApplicationEvent), the ContextRefresher
// (1) rebuilds the Environment — re-fetching this service's config from the config server — and then
// (2) disposes every refresh-scoped bean. The very next call rebuilds THIS bean against the new
// Environment, so its @Value fields re-read the updated values. Net effect: flip a flag in one place,
// hit one endpoint, and the running service adopts it — no redeploy, no dropped connections.
//
// Compare with StaticOrderConfig (same @Value fields, NO @RefreshScope): it stays frozen at its
// startup values until the process restarts. The ConfigController surfaces both side by side so the
// difference is observable, and ConfigRefreshAndSecretsTest proves it: after ContextRefresher.refresh()
// this bean is re-created (its instanceSeq increments) while the static one is not.
@Component
@RefreshScope
public class RefreshableOrderConfig {

    // Counts how many times this bean has been INSTANTIATED across the app's lifetime. Because a
    // @RefreshScope bean is re-created on every refresh, this number ticking up is the fingerprint of
    // a live refresh actually happening — the test asserts exactly that.
    private static final AtomicLong INSTANCES = new AtomicLong();

    private final long instanceSeq = INSTANCES.incrementAndGet();
    private final String greeting;
    private final boolean expressCheckoutEnabled;

    // The two config-backed values. The inline defaults (:...) keep the bean bindable when the config
    // server is offline (tests, cold start) — exactly the `optional:` philosophy of the rest of the app.
    // When the config server IS up, its order-service.yml supplies the real, centrally-tunable values.
    public RefreshableOrderConfig(
            @Value("${app.orders.greeting:OrderHub Orders API}") String greeting,
            @Value("${app.orders.express-checkout-enabled:false}") boolean expressCheckoutEnabled) {
        this.greeting = greeting;
        this.expressCheckoutEnabled = expressCheckoutEnabled;
    }

    public long getInstanceSeq() {
        return instanceSeq;
    }

    public String getGreeting() {
        return greeting;
    }

    public boolean isExpressCheckoutEnabled() {
        return expressCheckoutEnabled;
    }
}

package dev.dev48v.orderhub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

// Day 23 — the CONTROL in the refresh experiment: an ordinary singleton, deliberately NOT @RefreshScope.
//
// It binds the SAME two config-backed values as RefreshableOrderConfig, but as a plain Spring singleton
// it is created exactly ONCE at startup and its @Value fields are frozen from that moment. A POST to
// /actuator/refresh re-reads the Environment and re-creates refresh-scoped beans, but this one is left
// untouched — it keeps serving the OLD greeting / OLD flag until the whole process restarts. That is the
// default behaviour of the vast majority of beans, and it's exactly why @RefreshScope is opt-in: most
// config is fine to pick up only on restart, and you mark just the handful of beans that must flip live.
//
// The ConfigController shows this bean's values next to the refreshable bean's so a viewer can watch one
// update on refresh while the other stays stale; the test asserts this bean's instanceSeq does NOT change
// across a refresh.
@Component
public class StaticOrderConfig {

    private static final AtomicLong INSTANCES = new AtomicLong();

    private final long instanceSeq = INSTANCES.incrementAndGet();
    private final String greeting;
    private final boolean expressCheckoutEnabled;

    public StaticOrderConfig(
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

package dev.dev48v.orderhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Day 13 — externalised knobs for the rate limiter.
// WHY: the limit ("how many requests, how fast") is a policy decision that changes per
// environment — generous in dev so you never fight your own tests, tighter in prod to shield
// the database. Binding it to one immutable record (relaxed-bound from "app.ratelimit.*") means
// you retune it from application.yml, a profile file, or an env var (APP_RATELIMIT_CAPACITY=…)
// with no recompile. @DefaultValue keeps the app booting with a sane limit even if a key is missing.
//
// The two numbers together define a classic token bucket:
//   • capacity      — the bucket size; also the maximum burst a client can spend at once.
//   • refillPeriod  — how long it takes to refill `capacity` tokens from empty, refilled greedily
//                     (smoothly, token-by-token) rather than in one lump at the end of the window.
// So capacity=20, refillPeriod=1m means "20 requests per minute per client, ~1 token every 3s".
@ConfigurationProperties(prefix = "app.ratelimit")
public record RateLimitProperties(

        // Master switch. Off in tests that don't care about limiting; on everywhere real traffic flows.
        @DefaultValue("true") boolean enabled,

        // Bucket size = tokens available, and the largest burst one client can spend before throttling.
        @DefaultValue("20") long capacity,

        // Time to refill `capacity` tokens (greedy/continuous refill). Accepts ISO-8601 or Spring
        // duration text ("1m", "30s") thanks to relaxed binding.
        @DefaultValue("1m") Duration refillPeriod
) {
}

package dev.dev48v.orderhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Day 16 — externalised knobs for idempotency keys.
// WHY: whether idempotency is on, which header carries the key, how long a stored result is
// replayable, and how long an in-flight reservation may lock the key are all policy that varies
// by environment. Binding them to one immutable record (relaxed-bound from "app.idempotency.*")
// means you retune them from application.yml, a profile file, or an env var
// (APP_IDEMPOTENCY_TTL=…) with no recompile. @DefaultValue keeps the app booting with sane
// values even if a key is missing.
//
// The two durations play different roles in the safe-retry protocol:
//   • ttl     — how long a COMPLETED response stays in Redis and can be replayed. This is the
//               window in which a client-side retry / double-submit of the SAME action returns the
//               original result instead of creating a duplicate. 24h comfortably covers any sane
//               retry (a stuck request, a user refreshing, a mobile app resuming).
//   • lockTtl — how long the initial IN_PROGRESS reservation lives. It's short on purpose: if the
//               server crashes mid-request the reservation must expire so a legitimate retry can
//               proceed, rather than the key staying locked for the full ttl. On success we
//               overwrite the reservation with the COMPLETED entry at the full ttl.
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(

        // Master switch. Off in tests that don't exercise idempotency; on everywhere real POSTs flow.
        @DefaultValue("true") boolean enabled,

        // The request header that carries the client-generated key. "Idempotency-Key" is the de-facto
        // industry standard (Stripe, PayPal, the IETF draft), so clients and proxies recognise it.
        @DefaultValue("Idempotency-Key") String headerName,

        // How long a COMPLETED response is remembered and replayable. Accepts ISO-8601 or Spring
        // duration text ("24h", "30m") thanks to relaxed binding.
        @DefaultValue("24h") Duration ttl,

        // How long the IN_PROGRESS reservation locks the key before it self-expires. Deliberately
        // short so a crashed request can't wedge the key for the whole ttl.
        @DefaultValue("5m") Duration lockTtl
) {
}

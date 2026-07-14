package dev.dev48v.orderhub.config;

// Day 23 — a tiny helper so a secret can be OBSERVED (is it set? does it look right?) without ever
// being printed in full. The golden rule of secret handling is that the plaintext value should never
// leave the process: not in logs, not in an API response, not in an error message. But operators still
// need a way to sanity-check that the right credential is wired in, so we surface a MASKED form — a few
// leading/trailing characters as a fingerprint, the middle blanked. That's enough to tell "sk_live_…"
// from "sk_test_…" or a stale key, without leaking anything usable.
public final class SecretMasker {

    private SecretMasker() {
    }

    // Reveal the first and last 4 characters as a fingerprint; blank everything in between. Anything
    // short enough that a fingerprint would give too much away is fully hidden, and an unset/blank
    // value is reported as such (so a missing secret is visible as missing, not as an empty string).
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "(unset)";
        }
        if (secret.length() <= 8) {
            return "••••"; // ••••  — too short to fingerprint safely
        }
        return secret.substring(0, 4) + "…" + secret.substring(secret.length() - 4);
    }
}

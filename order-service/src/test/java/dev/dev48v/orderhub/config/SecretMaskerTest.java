package dev.dev48v.orderhub.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Day 23 — a fast, no-Spring unit test for the secret masker. It guards the ONE invariant that matters:
// whatever we surface for observability must never be the full plaintext, yet must stay a useful
// fingerprint. Pure function in, string out — no context to boot.
@DisplayName("Day 23 · SecretMasker — fingerprint without leaking")
class SecretMaskerTest {

    @Test
    @DisplayName("a long secret shows only first/last 4 chars, middle blanked")
    void masksMiddleOfLongSecret() {
        String masked = SecretMasker.mask("sk_live_1234567890abcd");
        assertThat(masked).isEqualTo("sk_l…abcd");
        // the plaintext is never fully present in the masked form
        assertThat(masked).doesNotContain("1234567890");
    }

    @Test
    @DisplayName("a short secret is fully hidden (too little to fingerprint safely)")
    void fullyHidesShortSecret() {
        assertThat(SecretMasker.mask("abc123")).isEqualTo("••••");
    }

    @Test
    @DisplayName("an unset/blank secret is reported as unset, not as an empty string")
    void reportsUnset() {
        assertThat(SecretMasker.mask(null)).isEqualTo("(unset)");
        assertThat(SecretMasker.mask("   ")).isEqualTo("(unset)");
    }
}

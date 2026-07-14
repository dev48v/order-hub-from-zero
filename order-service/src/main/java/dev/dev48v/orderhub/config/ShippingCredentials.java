package dev.dev48v.orderhub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Day 23 — a sensitive value that is kept OUT of the config repo and resolved at runtime.
//
// THE PROBLEM: it's tempting to drop a downstream API key straight into application.yml or the config
// server's order-service.yml. Never do that. Config lives in git; a committed secret is leaked the
// instant it's pushed, forever in history, visible to everyone with repo access — and rotating it means
// a commit. Secrets must not be plaintext in source control.
//
// THE FIX USED HERE (env-var placeholder — simplest + reproducible): the committed config holds only a
// PLACEHOLDER, `${SHIPPING_API_KEY:...}` (see order-service application.yml). At runtime Spring resolves
// it from the SHIPPING_API_KEY environment variable, which is injected by the platform (systemd unit,
// container env, CI secret) and never committed. If the var is absent we fall back to a clearly-labelled
// local placeholder that is obviously NOT a real secret, so the app still boots for local dev and tests.
// The real key's value therefore never appears in git — only the reference to it does.
//
// OTHER OPTIONS (documented, not wired here):
//   • Config Server ENCRYPTION — store `{cipher}AQB...` in the config repo; the server decrypts it with a
//     symmetric `encrypt.key` (or an RSA keystore) before serving. The ciphertext is safe to commit.
//   • HashiCorp VAULT / AWS Secrets Manager (the production answer) — a dedicated secrets store with
//     access policies, dynamic/leased credentials, automatic rotation and an audit trail. Spring Cloud
//     Vault plugs in as another PropertySource so `${...}` resolution is unchanged.
// Whatever the backend, the app code is identical: it reads a property; it never knows where the value
// physically came from. That indirection is the whole point.
@Component
public class ShippingCredentials {

    // The environment variable that supplies the real value in every deployed environment.
    static final String ENV_VAR = "SHIPPING_API_KEY";

    private final String apiKey;

    public ShippingCredentials(
            @Value("${app.orders.integrations.shipping-api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    // Package-private, for tests only — the raw value is never exposed through any public method or API.
    String resolvedKey() {
        return apiKey;
    }

    // The only form of the secret anything outside this class ever sees.
    public String maskedKey() {
        return SecretMasker.mask(apiKey);
    }

    // Did the value actually come from the environment (prod-shaped), or from the safe local default?
    public boolean fromEnvironment() {
        return System.getenv(ENV_VAR) != null;
    }

    // A human-readable note on WHERE the value was resolved from — useful for the observability endpoint.
    public String source() {
        return fromEnvironment()
                ? "environment variable ${" + ENV_VAR + "} (injected by the platform, never committed)"
                : "safe local placeholder (no " + ENV_VAR + " set) — deliberately NOT a real secret";
    }
}

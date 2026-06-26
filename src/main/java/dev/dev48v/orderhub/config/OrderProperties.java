package dev.dev48v.orderhub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 7 — type-safe, externalised configuration.
// WHY: scattering magic numbers (max quantity, page sizes) through the code makes them
// invisible and impossible to tune per environment. Binding them to one immutable record
// gives a single, documented home for the knobs: Spring relaxed-binds the "app.orders.*"
// keys (from application.yml, a profile file, or an env var like APP_ORDERS_MAXPAGESIZE)
// straight onto these components. A record is the ideal carrier — immutable and concise —
// and @DefaultValue means the app still boots with sane limits even if a key is missing.
@ConfigurationProperties(prefix = "app.orders")
public record OrderProperties(

        // The largest quantity a single order may request. Enforced in the service layer.
        @DefaultValue("1000") int maxQuantity,

        // The page size used when a client lists orders without asking for one.
        @DefaultValue("20") int defaultPageSize,

        // The hard ceiling on page size — a client can never pull more than this in one page.
        @DefaultValue("100") int maxPageSize
) {
}

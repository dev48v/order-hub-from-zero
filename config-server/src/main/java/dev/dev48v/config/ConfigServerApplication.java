package dev.dev48v.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

// Day 21 — the entry point for the CONFIG SERVER: the single source of truth for configuration.
//
// @EnableConfigServer is the whole trick: it flips this ordinary Spring Boot app into a running
// Spring Cloud Config Server. On boot it stands up an HTTP API that serves each service's merged
// configuration at /{application}/{profile}[/{label}] — e.g. GET /order-service/default returns
// order-service.yml layered on top of the shared application.yml. A config CLIENT (order-service,
// inventory-service) asks for its own name at startup and gets back exactly the properties meant
// for it, from ONE place instead of a copy-pasted local file.
//
// WHERE the config lives is chosen by the ACTIVE PROFILE, not by code. We run the `native` profile
// (see application.yml), so the server reads plain yml files from a search location on the classpath
// (classpath:/config) — the simplest, fully-reproducible backend, no external git remote to clone.
// Swap the profile to `git` and the same server would instead serve config from a git repository
// (the production-grade backend: versioned, audited, rollback-able) with no change to this class or
// to any client.
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}

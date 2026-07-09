package dev.dev48v.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

// Day 19 — the entry point for the service REGISTRY.
//
// @EnableEurekaServer is the whole trick: it flips this ordinary Spring Boot app into a running
// Netflix Eureka server. On boot it stands up the registry (an in-memory map of
// serviceName -> the instances currently registered under it) plus a REST API the clients use to
// register, heartbeat and query, and a dashboard at http://localhost:8761 showing who's registered.
//
// A Eureka server is itself a Eureka CLIENT by default — in a cluster, peers register with each other
// for high availability. We run a single, STANDALONE node (see application.yml): register-with-eureka
// and fetch-registry are turned OFF so this node doesn't try to register with, or replicate from, a
// peer that doesn't exist. That's the right shape for local dev and this series' scale.
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}

package dev.dev48v.inventory.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

// Day 24 — wires the service-token gate into inventory-service.
//
// @EnableConfigurationProperties binds service.auth.* onto ServiceAuthProperties (inventory-service's
// application class is a bare @SpringBootApplication with no properties wiring of its own, so we enable
// the record here where it's used).
//
// The filter is registered EXPLICITLY via a FilterRegistrationBean rather than as a @Component — the
// same choice order-service made for its RateLimitFilter, and for the same two reasons:
//   1. We can PIN it to the /api/* URL space and set its ORDER, so it runs right at the edge (before the
//      dispatcher touches a controller) and an unauthenticated request costs almost nothing to reject.
//   2. It stays OUT of @WebMvcTest slices. InventoryControllerTest (a @WebMvcTest) asserts the inventory
//      HTTP contract WITHOUT a token; registering the filter as a bean here means that slice never picks
//      it up, so those contract tests keep passing unchanged while the gate is still fully exercised by
//      its own focused ServiceTokenAuthFilterTest.
@Configuration
@EnableConfigurationProperties(ServiceAuthProperties.class)
public class ServiceAuthConfig {

    @Bean
    public FilterRegistrationBean<ServiceTokenAuthFilter> serviceTokenAuthFilterRegistration(
            ServiceAuthProperties props, ObjectMapper objectMapper) {
        FilterRegistrationBean<ServiceTokenAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ServiceTokenAuthFilter(props, objectMapper));
        // Only the API surface — /actuator/health (the load balancer's probe) is intentionally left open.
        registration.addUrlPatterns("/api/*");
        // Run at the very edge so an unauthenticated request is rejected before anything else does work.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("serviceTokenAuthFilter");
        return registration;
    }
}

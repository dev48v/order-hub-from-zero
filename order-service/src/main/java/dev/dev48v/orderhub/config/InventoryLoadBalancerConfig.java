package dev.dev48v.orderhub.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

// Day 22 — the per-service Spring Cloud LoadBalancer configuration for the "inventory-service" client.
//
// Days 19–21 already gave us client-side load balancing IMPLICITLY: the Eureka client starter pulls in
// spring-cloud-loadbalancer, and when Feign resolves @FeignClient(name = "inventory-service") to several
// instances, Spring Cloud's DEFAULT round-robin balancer picks one per call — no code from us. Today makes
// that machinery EXPLICIT and CUSTOMISED, so we own the two decisions a client-side balancer really makes:
//   1. WHERE does the instance list come from, and how is it filtered?   -> the ServiceInstanceListSupplier
//   2. HOW is one instance chosen from that list per request?            -> the ReactorLoadBalancer strategy
//
// WHY this is a plain class and NOT @Configuration / @Component: a LoadBalancer client config must live
// OUTSIDE the app's @ComponentScan. If Spring picked it up as a normal configuration, its beans would apply
// GLOBALLY to every load-balanced client. Instead it is referenced only by name from
// @LoadBalancerClient(name = "inventory-service", configuration = InventoryLoadBalancerConfig.class) on the
// app class, and Spring Cloud instantiates it in the ISOLATED child context that backs just the
// "inventory-service" client. Component scanning ignores this class precisely because it carries no
// stereotype annotation — the @Bean methods are only honoured inside that per-client child context. This is
// the documented Spring Cloud pattern; keep it un-annotated at the type level.
public class InventoryLoadBalancerConfig {

    // (1) THE INSTANCE LIST — discovery-driven, health-filtered, and cached.
    // The supplier is a small pipeline of decorators, each wrapping the previous one:
    //   • withBlockingDiscoveryClient() — the BASE. It asks Eureka's DiscoveryClient "which instances are
    //     registered under this service id right now?" There are NO hardcoded host:port URLs anywhere; scale
    //     inventory-service up or down and this list changes with the registry. "Blocking" = the servlet-stack
    //     variant (order-service is Spring MVC, not WebFlux), so it uses the blocking DiscoveryClient.
    //   • withBlockingHealthChecks(new RestTemplate()) — HEALTH-CHECK-BASED FILTERING. On a schedule it probes
    //     each instance's Actuator health endpoint (/actuator/health by default) over a RestTemplate and keeps
    //     only the instances reporting UP. So an instance that Eureka still lists but that is actually sick is
    //     removed from rotation — traffic rebalances onto the healthy ones without waiting for the registry
    //     lease to expire. The probe interval / path are tunable via spring.cloud.loadbalancer.health-check.*.
    //   • withCaching() — the OUTERMOST layer. It caches the resolved (discovered + health-checked) instance
    //     list for a short TTL so we don't re-hit discovery and re-probe health on every single request; the
    //     cache is refreshed in the background. This is the standard, production-shaped ordering:
    //         cache( healthChecks( discovery ) ).
    // build(context) needs the surrounding child ApplicationContext to pull the shared beans (DiscoveryClient,
    // LoadBalancer cache manager, health-check properties) it decorates.
    @Bean
    public ServiceInstanceListSupplier inventoryServiceInstanceListSupplier(ConfigurableApplicationContext context) {
        return ServiceInstanceListSupplier.builder()
                .withBlockingDiscoveryClient()
                .withBlockingHealthChecks(new RestTemplate())
                .withCaching()
                .build(context);
    }

    // (2) THE STRATEGY — which instance from the (healthy) list serves this call.
    // Spring Cloud LoadBalancer is the successor to Netflix Ribbon; a strategy is a ReactorLoadBalancer that,
    // given the supplier above, returns one instance per request. We expose BOTH built-in strategies and let a
    // single property choose between them at startup (no recompile):
    //   • round-robin (DEFAULT) — RoundRobinLoadBalancer walks the list in order, one after another, so N calls
    //     across M instances spread evenly (N/M each). The fair, predictable default.
    //   • random — RandomLoadBalancer picks a uniformly random instance each time; even distribution only in
    //     expectation, but stateless and contention-free (no shared position counter).
    // Set orderhub.loadbalancer.strategy=random in config to flip inventory-service onto random selection.
    //
    // LoadBalancerClientFactory.PROPERTY_NAME resolves to the service id ("inventory-service") for THIS child
    // context, and getLazyProvider hands the chosen balancer a lazy handle to the supplier bean above — lazy so
    // the balancer is constructed cheaply and the supplier is only materialised on the first real call.
    // Declaring this bean makes Spring Cloud's default RoundRobinLoadBalancer (which is @ConditionalOnMissingBean)
    // back off, so OUR strategy is the one used for inventory-service.
    @Bean
    public ReactorLoadBalancer<ServiceInstance> inventoryLoadBalancer(
            Environment environment, LoadBalancerClientFactory loadBalancerClientFactory) {
        String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        String strategy = environment.getProperty("orderhub.loadbalancer.strategy", "round-robin");
        ObjectProvider<ServiceInstanceListSupplier> supplierProvider =
                loadBalancerClientFactory.getLazyProvider(serviceId, ServiceInstanceListSupplier.class);
        if ("random".equalsIgnoreCase(strategy)) {
            return new RandomLoadBalancer(supplierProvider, serviceId);
        }
        return new RoundRobinLoadBalancer(supplierProvider, serviceId);
    }
}

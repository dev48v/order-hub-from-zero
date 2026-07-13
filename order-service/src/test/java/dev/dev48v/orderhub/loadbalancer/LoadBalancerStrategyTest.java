package dev.dev48v.orderhub.loadbalancer;

import dev.dev48v.orderhub.config.InventoryLoadBalancerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Day 22 — proves the client-side load balancer is REAL and CUSTOMISED, with NO live instances and no
// registry. Two things are under test:
//   1. The round-robin STRATEGY genuinely rotates evenly across an instance list — the behaviour that makes
//      requests spread across scaled-out instances. We feed a fixed 3-instance supplier straight into a real
//      RoundRobinLoadBalancer and count who it picks.
//   2. InventoryLoadBalancerConfig honours the orderhub.loadbalancer.strategy property — building a
//      RoundRobinLoadBalancer by default and a RandomLoadBalancer when asked. This is the switch the day adds.
// Both are plain, fast unit tests: the ServiceInstanceListSupplier is a hand-built fixed list (or a Mockito
// stub), so nothing has to boot, discover, or health-check. That's the standard way to test a balancer.
@DisplayName("Day 22 · Spring Cloud LoadBalancer — strategy + round-robin distribution")
class LoadBalancerStrategyTest {

    private static final String SERVICE_ID = "inventory-service";

    private static ServiceInstance instance(int port) {
        return new DefaultServiceInstance(SERVICE_ID + ":" + port, SERVICE_ID, "127.0.0.1", port, false);
    }

    // A supplier that always returns the same fixed instance list — stands in for "discovery returned these".
    private static ServiceInstanceListSupplier fixedSupplier(List<ServiceInstance> instances) {
        return new ServiceInstanceListSupplier() {
            @Override
            public String getServiceId() {
                return SERVICE_ID;
            }

            @Override
            public Flux<List<ServiceInstance>> get() {
                return Flux.just(instances);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ServiceInstanceListSupplier> providerOf(ServiceInstanceListSupplier supplier) {
        ObjectProvider<ServiceInstanceListSupplier> provider = mock(ObjectProvider.class);
        // RoundRobinLoadBalancer resolves the supplier via getIfAvailable(defaultSupplier).
        when(provider.getIfAvailable(any())).thenReturn(supplier);
        return provider;
    }

    @Test
    @DisplayName("round-robin spreads N calls evenly across the instances (N/M each)")
    void roundRobinCyclesEvenlyAcrossInstances() {
        List<ServiceInstance> instances = List.of(instance(8091), instance(8092), instance(8093));
        RoundRobinLoadBalancer balancer = new RoundRobinLoadBalancer(providerOf(fixedSupplier(instances)), SERVICE_ID);

        TreeMap<Integer, Integer> hitsByPort = new TreeMap<>();
        for (int i = 0; i < 9; i++) {
            Response<ServiceInstance> response = balancer.choose().block();
            assertThat(response).isNotNull();
            assertThat(response.hasServer()).isTrue();
            hitsByPort.merge(response.getServer().getPort(), 1, Integer::sum);
        }

        // Round-robin walks the list in order, so 9 calls across 3 instances hit each one EXACTLY 3 times,
        // whatever the (randomised) starting position — that even split is the property under test.
        assertThat(hitsByPort).hasSize(3);
        assertThat(hitsByPort.values()).allMatch(count -> count == 3);
    }

    @Test
    @DisplayName("a single healthy instance always wins")
    void singleInstanceAlwaysChosen() {
        RoundRobinLoadBalancer balancer =
                new RoundRobinLoadBalancer(providerOf(fixedSupplier(List.of(instance(8091)))), SERVICE_ID);

        for (int i = 0; i < 5; i++) {
            Response<ServiceInstance> response = balancer.choose().block();
            assertThat(response).isNotNull();
            assertThat(response.getServer().getPort()).isEqualTo(8091);
        }
    }

    @Test
    @DisplayName("config builds a RoundRobinLoadBalancer by default")
    void defaultStrategyIsRoundRobin() {
        ReactorLoadBalancer<ServiceInstance> balancer = buildBalancerWithStrategy(null);
        assertThat(balancer).isInstanceOf(RoundRobinLoadBalancer.class);
    }

    @Test
    @DisplayName("config builds a RandomLoadBalancer when strategy=random")
    void randomStrategySelectsRandomLoadBalancer() {
        ReactorLoadBalancer<ServiceInstance> balancer = buildBalancerWithStrategy("random");
        assertThat(balancer).isInstanceOf(RandomLoadBalancer.class);
    }

    // Drives InventoryLoadBalancerConfig.inventoryLoadBalancer(...) with a MockEnvironment + a stubbed factory,
    // so we test the strategy SELECTION without a Spring context or a real load-balancer child context.
    @SuppressWarnings("unchecked")
    private ReactorLoadBalancer<ServiceInstance> buildBalancerWithStrategy(String strategy) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(LoadBalancerClientFactory.PROPERTY_NAME, SERVICE_ID);
        if (strategy != null) {
            environment.setProperty("orderhub.loadbalancer.strategy", strategy);
        }
        LoadBalancerClientFactory factory = mock(LoadBalancerClientFactory.class);
        when(factory.getLazyProvider(eq(SERVICE_ID), eq(ServiceInstanceListSupplier.class)))
                .thenReturn(mock(ObjectProvider.class));

        return new InventoryLoadBalancerConfig().inventoryLoadBalancer(environment, factory);
    }
}

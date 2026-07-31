package dev.dev48v.orderhub.security;

import dev.dev48v.orderhub.config.MethodSecurityConfig;
import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.events.OrderEventPublisher;
import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import dev.dev48v.orderhub.observability.OrderMetrics;
import dev.dev48v.orderhub.outbox.OutboxWriter;
import dev.dev48v.orderhub.repository.OrderRepository;
import dev.dev48v.orderhub.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Day 40 — METHOD-LEVEL SECURITY slice test (Phase 5 · security). It proves the whole contract the day calls
// for, WITHOUT booting the full app or the URL filter chains: method security is turned ON
// (orderhub.security.method.enabled=true) so MethodSecurityConfig installs the @PreAuthorize / @PostAuthorize
// advisors and the ADMIN > USER hierarchy, and the OrderService bean is a REAL bean wrapped by those advisors.
// Its collaborators are mocks (this is about authZ, not business logic). @WithMockUser stamps the caller's
// role/username into the SecurityContext, then we assert:
//   • a USER is DENIED a write (AccessDeniedException) but an ADMIN is ALLOWED;
//   • a USER is ALLOWED a read;
//   • the @PreAuthorize SpEL OWNERSHIP rule (a user may list only their OWN orders; an admin, anyone's);
//   • the @PostAuthorize OWNERSHIP rule (a user receives an order only if it is theirs);
//   • the ROLE HIERARCHY — an ADMIN (holding only ROLE_ADMIN) passes a ROLE_USER-gated read, i.e. inherits it;
//   • the GATE itself — the RoleHierarchy bean exists only when the flag is on (ApplicationContextRunner).
// The DEFAULT-OFF path (annotations dormant) is covered by every OTHER test in the suite — they run with the
// flag off and are unaffected, which is the entire point of the gate.
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {MethodSecurityConfig.class, OrderMethodSecurityTest.TestBeans.class})
@TestPropertySource(properties = "orderhub.security.method.enabled=true")
@DisplayName("Day 40 · method security — @PreAuthorize/@PostAuthorize SpEL + ADMIN>USER role hierarchy")
class OrderMethodSecurityTest {

    // A minimal context: MethodSecurityConfig (the advisors + hierarchy) plus the REAL OrderService built over
    // mocked collaborators. Because @EnableMethodSecurity is active here, the OrderService bean is proxied and
    // its @PreAuthorize / @PostAuthorize annotations are enforced on every call.
    @Configuration
    static class TestBeans {
        @Bean
        OrderProperties orderProperties() {
            return new OrderProperties(1000, 20, 100);
        }

        @Bean
        OrderService orderService(OrderRepository repository,
                                  OrderProperties properties,
                                  InventoryServiceClient inventory,
                                  OrderEventPublisher events,
                                  OutboxWriter outbox,
                                  ObjectProvider<OrderMetrics> metricsProvider) {
            return new OrderService(repository, properties, inventory, events, outbox, metricsProvider);
        }
    }

    @MockBean
    private OrderRepository repository;
    @MockBean
    private InventoryServiceClient inventory;
    @MockBean
    private OrderEventPublisher events;
    @MockBean
    private OutboxWriter outbox;

    @org.springframework.beans.factory.annotation.Autowired
    private OrderService orderService;

    // A fresh PLACED order owned by "Ada" per test (confirmOrder mutates it, so it must not be shared).
    private Order ada;

    @BeforeEach
    void stubRepository() {
        ada = Order.rehydrate("order-1", "Ada", "Keyboard", 2, OrderStatus.PLACED, Instant.now());
        when(repository.findById("order-1")).thenReturn(Optional.of(ada));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findAll()).thenReturn(List.of(ada));
    }

    // ---------- write: USER denied, ADMIN allowed ----------
    @Test
    @WithMockUser(username = "u", roles = "USER")
    @DisplayName("a USER calling a WRITE (placeOrder → ROLE_ADMIN) is denied with AccessDeniedException")
    void userIsDeniedAWrite() {
        assertThatThrownBy(() -> orderService.placeOrder("Ada", "Keyboard", 2))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("an ADMIN calling a WRITE (confirmOrder → ROLE_ADMIN) is allowed and the order is CONFIRMED")
    void adminIsAllowedAWrite() {
        Order result = orderService.confirmOrder("order-1");
        assertThat(result.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    // ---------- read: USER allowed ----------
    @Test
    @WithMockUser(username = "u", roles = "USER")
    @DisplayName("a USER calling a READ (getOrder → ROLE_USER) is allowed")
    void userIsAllowedARead() {
        assertThat(orderService.getOrder("order-1").getCustomer()).isEqualTo("Ada");
    }

    @Test
    @WithMockUser(username = "guest", roles = "GUEST")
    @DisplayName("an authenticated caller WITHOUT ROLE_USER is denied a READ")
    void insufficientRoleIsDeniedARead() {
        assertThatThrownBy(() -> orderService.getOrder("order-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- @PreAuthorize SpEL ownership: #ownerId == authentication.name ----------
    @Test
    @WithMockUser(username = "Ada", roles = "USER")
    @DisplayName("SpEL ownership — a USER may list their OWN orders (#ownerId == authentication.name)")
    void ownerListsOwnOrders() {
        assertThat(orderService.listOrdersForOwner("Ada")).extracting(Order::getCustomer).containsOnly("Ada");
    }

    @Test
    @WithMockUser(username = "Ada", roles = "USER")
    @DisplayName("SpEL ownership — a USER may NOT list someone else's orders")
    void userCannotListOthersOrders() {
        assertThatThrownBy(() -> orderService.listOrdersForOwner("Bob"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "root", roles = "ADMIN")
    @DisplayName("SpEL ownership — an ADMIN may list ANY owner's orders (hasRole('ADMIN') branch)")
    void adminListsAnyOwnersOrders() {
        assertThatCode(() -> orderService.listOrdersForOwner("Bob")).doesNotThrowAnyException();
    }

    // ---------- @PostAuthorize SpEL ownership: returnObject.customer == authentication.name ----------
    @Test
    @WithMockUser(username = "Ada", roles = "USER")
    @DisplayName("@PostAuthorize ownership — a USER receives an order that is theirs")
    void ownerReceivesOwnOrder() {
        assertThat(orderService.getOrderForOwner("order-1").getId()).isEqualTo("order-1");
    }

    @Test
    @WithMockUser(username = "Bob", roles = "USER")
    @DisplayName("@PostAuthorize ownership — a USER is denied an order that belongs to someone else")
    void nonOwnerIsDeniedTheOrder() {
        assertThatThrownBy(() -> orderService.getOrderForOwner("order-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------- role hierarchy: ADMIN > USER ----------
    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("role hierarchy — an ADMIN (only ROLE_ADMIN) passes a ROLE_USER-gated read, inheriting USER")
    void adminInheritsUserPermissionsForReads() {
        // getOrder requires hasRole('USER'); this principal holds ONLY ROLE_ADMIN. It succeeds solely because
        // the ADMIN > USER hierarchy makes ROLE_ADMIN reach ROLE_USER. Without the hierarchy this would be 403.
        assertThat(orderService.getOrder("order-1").getId()).isEqualTo("order-1");
    }

    // ---------- the GATE + the hierarchy definition, proven with an isolated throwaway context ----------
    @Test
    @DisplayName("gate — RoleHierarchy bean exists ONLY when the flag is on, and encodes ADMIN > USER")
    void methodSecurityIsGatedByFlagAndEncodesHierarchy() {
        ApplicationContextRunner runner =
                new ApplicationContextRunner().withUserConfiguration(MethodSecurityConfig.class);

        // flag OFF (default) → the whole @ConditionalOnProperty config backs off, no RoleHierarchy bean.
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RoleHierarchy.class));

        // flag ON → the hierarchy bean is created and ROLE_ADMIN reaches ROLE_USER.
        runner.withPropertyValues("orderhub.security.method.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(RoleHierarchy.class);
            RoleHierarchy hierarchy = ctx.getBean(RoleHierarchy.class);
            assertThat(hierarchy.getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .extracting(GrantedAuthority::getAuthority)
                    .contains("ROLE_ADMIN", "ROLE_USER");
        });
    }
}

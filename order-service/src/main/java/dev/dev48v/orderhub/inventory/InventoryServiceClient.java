package dev.dev48v.orderhub.inventory;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

// Day 18 — the DECLARATIVE HTTP client for the inventory-service REST API.
//
// This interface has NO implementation we write. @EnableFeignClients (on OrderHubApplication) finds it,
// and Spring Cloud OpenFeign generates a proxy bean that implements each method by making the HTTP call
// its annotations describe. The Spring MVC annotations here (@GetMapping/@PostMapping/@PathVariable/
// @RequestBody) are the SAME ones inventory-service's controller uses to SERVE these paths — Feign reads
// them (via the SpringMvcContract) to build the outbound REQUEST instead of to route an inbound one.
// So getStock("KEYBOARD-001") becomes  GET  {url}/api/inventory/KEYBOARD-001, and
//    reserve("KEYBOARD-001", body) becomes  POST {url}/api/inventory/KEYBOARD-001/reserve  with the body
// serialised to JSON and the JSON response bound back onto StockView. The call site reads like a local
// method call; the network hop is hidden behind the interface.
//
// Day 19 — NAME-BASED DISCOVERY. The `name` is now the SERVICE ID Feign resolves through the Eureka
// registry: Feign asks the DiscoveryClient "which instances are registered under 'inventory-service'?"
// and Spring Cloud LoadBalancer picks one per call. So order-service no longer needs to know WHERE
// inventory-service is — only its NAME. When inventory-service moves, restarts on a new host, or scales
// to several instances, nothing here changes; the registry tracks the live addresses.
//
//   • name — the logical SERVICE ID. It must match inventory-service's spring.application.name. This is
//     the only thing the caller needs; the address comes from the registry at call time.
//   • url  — DROPPED for normal operation. We keep `${inventory.service.url:}` with an EMPTY default: when
//     the property is absent (dev/prod) the empty url means "no absolute url" → Feign uses discovery +
//     load-balancing by name. The escape hatch is for TESTS ONLY — InventoryServiceClientTest SETS this
//     property to point the client straight at an OkHttp MockWebServer, so it can exercise the real HTTP
//     path without a registry. A non-empty url short-circuits discovery; an empty one enables it.
//
// Error behaviour: any non-2xx response (404 unknown SKU, 409 insufficient stock, 5xx, or a connect
// failure) surfaces as a feign.FeignException from the called method — Feign's default ErrorDecoder. The
// order flow catches it and translates it into a domain-level failure (see OrderService.reserveStock).
@FeignClient(name = "inventory-service", url = "${inventory.service.url:}")
public interface InventoryServiceClient {

    // GET the current stock for one SKU. Mirrors InventoryController.getOne(sku) on inventory-service.
    @GetMapping("/api/inventory/{sku}")
    StockView getStock(@PathVariable("sku") String sku);

    // POST a reservation against a SKU; returns the NEW stock level so the caller sees the effect.
    // Mirrors InventoryController.reserve(sku, request). This is the write the order flow drives on Day 18.
    @PostMapping("/api/inventory/{sku}/reserve")
    StockView reserve(@PathVariable("sku") String sku, @RequestBody ReserveRequest request);

    // Day 22 — ask "which inventory-service instance are you?". This call goes through the SAME name-based,
    // load-balanced path as the others: Feign resolves "inventory-service" to the live instance list and the
    // client-side load balancer (see InventoryLoadBalancerConfig) picks one. Because the response carries the
    // answering instance's identity, hitting this repeatedly reveals the load balancer's distribution — the
    // way we make round-robin (vs random) visible without any hardcoded instance address.
    @GetMapping("/api/inventory/instance")
    InstanceView whichInstance();
}

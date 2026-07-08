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
//   • name — a logical id for this client (shows up in logs/metrics). From Day 19 (Eureka) this same
//     name becomes the SERVICE ID Feign resolves through discovery + load-balancing, and the hardcoded
//     url below disappears. Today there is no registry, so we must tell Feign WHERE the service is.
//   • url  — the absolute base URL, taken from the `inventory.service.url` property with a localhost:8081
//     default (inventory-service's port). Externalised so dev/prod point it at different hosts with no
//     recompile — the interim, pre-service-discovery way to locate a dependency.
//
// Error behaviour: any non-2xx response (404 unknown SKU, 409 insufficient stock, 5xx, or a connect
// failure) surfaces as a feign.FeignException from the called method — Feign's default ErrorDecoder. The
// order flow catches it and translates it into a domain-level failure (see OrderService.reserveStock).
@FeignClient(name = "inventory-service", url = "${inventory.service.url:http://localhost:8081}")
public interface InventoryServiceClient {

    // GET the current stock for one SKU. Mirrors InventoryController.getOne(sku) on inventory-service.
    @GetMapping("/api/inventory/{sku}")
    StockView getStock(@PathVariable("sku") String sku);

    // POST a reservation against a SKU; returns the NEW stock level so the caller sees the effect.
    // Mirrors InventoryController.reserve(sku, request). This is the write the order flow drives on Day 18.
    @PostMapping("/api/inventory/{sku}/reserve")
    StockView reserve(@PathVariable("sku") String sku, @RequestBody ReserveRequest request);
}

package dev.dev48v.inventory.web;

import dev.dev48v.inventory.stock.InventoryService;
import dev.dev48v.inventory.web.dto.ReserveRequest;
import dev.dev48v.inventory.web.dto.StockView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Day 17 — the Inventory service's OWN HTTP surface, served by its OWN Spring Boot app on port 8081.
// This is the seam that makes the split real: instead of order-service calling an in-process Java
// method, the inventory capability now lives behind a network API that ANY service can call.
//
// The paths intentionally mirror order-service's old in-process inventory endpoint (/api/inventory)
// so the contract feels familiar — but this is a genuinely separate process. From Day 18,
// order-service will call GET /api/inventory/{sku} and POST /api/inventory/{sku}/reserve here via an
// OpenFeign client instead of the in-process stub it still holds today. Today the two just run side
// by side; this endpoint is already usable directly (curl localhost:8081/api/inventory).
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    // The whole catalogue's current stock.
    @GetMapping
    public List<StockView> list() {
        return inventory.listStock().stream().map(StockView::from).toList();
    }

    // Current stock for one SKU. Unknown SKU → 404 (see InventoryExceptionHandler).
    @GetMapping("/{sku}")
    public StockView getOne(@PathVariable String sku) {
        return StockView.from(inventory.getStock(sku));
    }

    // Reserve units against an order. Returns the NEW stock level so the caller sees the effect.
    // Unknown SKU → 404; not enough stock → 409; bad quantity → 400. This is the write the order
    // flow will drive over the wire from Day 18.
    @PostMapping("/{sku}/reserve")
    public StockView reserve(@PathVariable String sku, @Valid @RequestBody ReserveRequest request) {
        return StockView.from(inventory.reserve(sku, request.quantity()));
    }
}

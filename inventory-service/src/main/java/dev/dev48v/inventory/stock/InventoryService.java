package dev.dev48v.inventory.stock;

import dev.dev48v.inventory.domain.StockItem;
import org.springframework.stereotype.Service;

import java.util.List;

// Day 17 — the inventory service layer: thin orchestration over the repository + the domain rules.
// Same shape as order-service's OrderService (constructor injection, no web types leaking in), so
// the codebase reads consistently across services even though they're now separate apps. This owns
// the two operations the Inventory context offers the rest of the system: READ current stock, and
// RESERVE units against an order.
@Service
public class InventoryService {

    private final StockRepository repository;

    public InventoryService(StockRepository repository) {
        this.repository = repository;
    }

    // The whole catalogue's current stock — used by the list endpoint and handy for demos.
    public List<StockItem> listStock() {
        return repository.findAll();
    }

    // Current stock for one SKU. Unknown SKU is a caller error (→ 404), not an empty result.
    public StockItem getStock(String sku) {
        return repository.findBySku(sku)
                .orElseThrow(() -> new UnknownSkuException(sku));
    }

    // Reserve units for an order. Looks up the SKU (404 if unknown), then delegates the "can we
    // afford this?" invariant to the domain object (409 if not), then persists the new level. When
    // order-service starts calling this over HTTP on Day 18, THIS is the operation it will invoke.
    public StockItem reserve(String sku, int quantity) {
        StockItem item = getStock(sku);
        item.reserve(quantity);          // domain enforces "never over-commit"
        return repository.save(item);
    }

    // Day 28 — the COMPENSATION for reserve(): put previously-reserved units back on hand. When the
    // choreography saga cancels an order (payment declined, or a downstream failure), inventory-service
    // reacts by RELEASING the stock it had held for that order — the inverse of the reservation it made on
    // OrderPlaced. This is how a distributed saga "rolls back" a step that has no shared DB transaction: not
    // by undoing a commit, but by applying a compensating operation. The symmetric domain method (replenish)
    // guards the invariant just like reserve() does.
    public StockItem release(String sku, int quantity) {
        StockItem item = getStock(sku);
        item.replenish(quantity);        // symmetric with reserve — units go back on hand
        return repository.save(item);
    }
}

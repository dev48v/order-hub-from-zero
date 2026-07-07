package dev.dev48v.inventory.stock;

import dev.dev48v.inventory.domain.StockItem;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Day 17 — the in-memory adapter behind the StockRepository port. A ConcurrentHashMap keyed by SKU,
// seeded at construction with a starter catalogue so the service is useful the moment it boots.
// WHY in-memory (not JPA/Postgres like order-service): today's story is the SERVICE SPLIT, not
// persistence — order-service already demonstrates the JPA + Flyway stack, and re-teaching it here
// would just add noise. Keeping Inventory's store trivial makes the extraction the clear focus, and
// because the service depends only on the port, promoting this to a real database later is a
// drop-in adapter swap. ConcurrentHashMap keeps concurrent reservations from corrupting the map;
// the per-item reserve() invariant is guarded inside StockItem.
@Repository
public class InMemoryStockRepository implements StockRepository {

    private final ConcurrentHashMap<String, StockItem> bySku = new ConcurrentHashMap<>();

    public InMemoryStockRepository() {
        seed(new StockItem("KEYBOARD-001", "Mechanical keyboard", 42));
        seed(new StockItem("MOUSE-001", "Wireless mouse", 30));
        seed(new StockItem("HUB-001", "USB-C hub", 15));
        seed(new StockItem("MONITOR-4K", "4K monitor", 7));
        seed(new StockItem("WEBCAM-001", "Webcam", 12));
        seed(new StockItem("STAND-001", "Laptop stand", 0)); // deliberately out of stock for demos
    }

    private void seed(StockItem item) {
        bySku.put(item.sku(), item);
    }

    @Override
    public Optional<StockItem> findBySku(String sku) {
        return Optional.ofNullable(bySku.get(sku));
    }

    @Override
    public List<StockItem> findAll() {
        return List.copyOf(bySku.values());
    }

    @Override
    public StockItem save(StockItem item) {
        bySku.put(item.sku(), item);
        return item;
    }
}

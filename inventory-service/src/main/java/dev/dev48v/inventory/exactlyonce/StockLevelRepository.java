package dev.dev48v.inventory.exactlyonce;

import org.springframework.data.jpa.repository.JpaRepository;

// Day 32 — the persistent stock repository. The exactly-once processor loads a SKU, mutates its availability,
// and saves it — all inside the same transaction as the dedup marker write. Standard Spring Data JPA; no
// custom queries needed (findById + save cover the reservation).
public interface StockLevelRepository extends JpaRepository<StockLevel, String> {
}

package dev.dev48v.inventory.exactlyonce;

import org.springframework.data.jpa.repository.JpaRepository;

// Day 32 — the dedup store's repository. The only operation the exactly-once path needs beyond save() is
// existsById(key): given a record's (topic, partition, offset) key, has this exact delivery already been
// processed? That single boolean, checked inside the processing transaction, is the redelivery guard.
// Spring Data implements it from the interface — no query to write.
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventKey> {
}

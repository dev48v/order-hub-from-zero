package dev.dev48v.orderhub.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// Day 30 — Spring Data JPA generates the implementation of this at runtime, exactly like
// SpringDataOrderRepository. It gives the outbox its two access paths for free:
//   • save(...) — used by OutboxWriter to INSERT a pending row inside the order's transaction.
//   • the derived query below — used by OutboxRelay to fetch the next batch of unsent rows.
//
// The relay's hot query is "the oldest UNSENT rows first", so the method name encodes exactly that:
// findByProcessedFalseOrderByCreatedAtAsc — WHERE processed = false ORDER BY created_at ASC. It maps
// precisely onto the (processed, created_at) index created in V3, so the poll stays cheap even with a
// large backlog. A Pageable caps how many rows one poll drains (the relay passes PageRequest.of(0, batchSize)).
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    // The next slice of not-yet-published rows, oldest first — the relay's per-tick work list.
    List<OutboxEvent> findByProcessedFalseOrderByCreatedAtAsc(Pageable pageable);
}

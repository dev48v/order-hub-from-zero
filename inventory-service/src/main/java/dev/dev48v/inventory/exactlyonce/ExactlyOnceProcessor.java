package dev.dev48v.inventory.exactlyonce;

import dev.dev48v.inventory.events.OrderPlacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Day 32 — the heart of the EXACTLY-ONCE (effectively-once) pattern: the READ-PROCESS-WRITE transaction.
//
// Kafka's own guarantees only reach so far. WITHIN Kafka you can get exactly-once — the idempotent producer
// stamps sequence numbers so a retried publish is de-duplicated by the broker, and transactions tie a batch
// of writes to the consumer-offset commit so "process + advance offset" is atomic. But that guarantee ends at
// the broker. The moment a consumer writes to an EXTERNAL system (our database), there is a gap Kafka cannot
// close: the app can commit its DB work and then crash BEFORE its offset is committed, so on restart Kafka —
// keeping its at-least-once promise — redelivers the SAME record. Without protection the reservation would be
// applied twice. End-to-end "effectively once" therefore needs a consumer-side dedup married to the DB write:
//
//   1. READ    — is this exact record (topic, partition, offset) already in processed_events?
//   2. PROCESS — if not, apply the business effect (reserve stock in stock_levels).
//   3. WRITE   — insert the processed_events marker.
//
// Steps 1-3 run in ONE @Transactional local database transaction. Because the marker and the reservation
// commit atomically, a redelivery after a crash sees the marker (step 1) and returns DUPLICATE_SKIPPED
// without touching stock. And because the offset is committed (MANUAL ack) only AFTER this method returns —
// i.e. after the DB has committed — the only failure window left is "committed DB, not-yet-acked", which is
// exactly the case the dedup handles. Net effect: the reservation happens exactly once, no matter how many
// times Kafka delivers the record.
@Service
public class ExactlyOnceProcessor {

    private static final Logger log = LoggerFactory.getLogger(ExactlyOnceProcessor.class);

    private final ProcessedEventRepository processedEvents;
    private final StockLevelRepository stockLevels;

    public ExactlyOnceProcessor(ProcessedEventRepository processedEvents, StockLevelRepository stockLevels) {
        this.processedEvents = processedEvents;
        this.stockLevels = stockLevels;
    }

    // Process ONE delivery atomically. `topic`/`partition`/`offset` are the record's physical coordinates
    // (the dedup key); `event` is the decoded payload. Runs in a single transaction: the dedup check, the
    // reservation, and the marker insert either all commit together or all roll back together.
    @Transactional
    public ProcessOutcome process(String topic, int partition, long offset, OrderPlacedEvent event) {
        ProcessedEventKey key = new ProcessedEventKey(topic, partition, offset);

        // 1. READ — has this exact record already been fully processed? If so, this is a redelivery: skip the
        //    business effect entirely. This is the check that makes reprocessing safe.
        if (processedEvents.existsById(key)) {
            log.info("Duplicate delivery {} (order {}, event {}) - already processed, skipping",
                    key, event.orderId(), event.eventId());
            return ProcessOutcome.DUPLICATE_SKIPPED;
        }

        // 2. PROCESS — apply the business effect, deciding the outcome from the current persistent stock.
        ProcessOutcome outcome;
        StockLevel stock = stockLevels.findById(event.item()).orElse(null);
        if (stock == null) {
            // Graceful business outcome: the order names a SKU this service doesn't stock. Record it so it is
            // not retried; do not touch stock.
            outcome = ProcessOutcome.UNKNOWN_SKU;
            log.warn("Unknown SKU '{}' on order {} - recording, no reservation", event.item(), event.orderId());
        } else if (!stock.canReserve(event.quantity())) {
            // Graceful business outcome: not enough on hand. Record it (so it is not retried) and leave stock as is.
            outcome = ProcessOutcome.INSUFFICIENT_STOCK;
            log.warn("Insufficient stock to reserve {} x {} for order {} ({} available)",
                    event.quantity(), event.item(), event.orderId(), stock.getAvailable());
        } else {
            stock.reserve(event.quantity());
            stockLevels.save(stock);
            outcome = ProcessOutcome.RESERVED;
            log.info("Reserved {} x {} for order {} - {} units remaining",
                    event.quantity(), event.item(), event.orderId(), stock.getAvailable());
        }

        // 3. WRITE — the dedup marker, in the SAME transaction as the reservation above. This is the write
        //    that makes step 1 meaningful on the next delivery. If anything here (or the commit) fails, the
        //    reservation rolls back with it, so state stays consistent and the record is legitimately retried.
        processedEvents.save(ProcessedEvent.of(key, event.eventId(), event.orderId(), outcome.name()));
        return outcome;
    }
}

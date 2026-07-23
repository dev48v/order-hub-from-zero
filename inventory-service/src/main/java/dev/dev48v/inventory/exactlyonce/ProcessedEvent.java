package dev.dev48v.inventory.exactlyonce;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

// Day 32 — one row of the dedup store (maps onto the processed_events table from V1). It records that a
// specific Kafka record (identified by its topic/partition/offset key) has been FULLY processed, so a later
// redelivery of that same record can be recognised and skipped.
//
// The critical property is that this row is INSERTED in the SAME database transaction as the business effect
// (the stock reservation in stock_levels): ExactlyOnceProcessor writes both, and they commit atomically.
// That atomicity is what makes the marker trustworthy — if it is present, the reservation definitely happened;
// if a crash rolled the transaction back, neither exists and the record will genuinely be reprocessed. The
// extra columns (eventId, orderId, outcome, processedAt) are for audit and are not part of the identity.
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @EmbeddedId
    private ProcessedEventKey id;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    // JPA requires a no-arg constructor to instantiate rows on load.
    protected ProcessedEvent() {
    }

    private ProcessedEvent(ProcessedEventKey id, String eventId, String orderId, String outcome,
                           Instant processedAt) {
        this.id = id;
        this.eventId = eventId;
        this.orderId = orderId;
        this.outcome = outcome;
        this.processedAt = processedAt;
    }

    // Factory for a freshly-processed record's marker. Called from the processor inside the reservation's tx.
    public static ProcessedEvent of(ProcessedEventKey id, String eventId, String orderId, String outcome) {
        return new ProcessedEvent(id, eventId, orderId, outcome, Instant.now());
    }

    public ProcessedEventKey getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}

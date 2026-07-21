package dev.dev48v.orderhub.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

// Day 30 — the JPA mapping for one row of the transactional outbox (maps onto the outbox_event table
// created by V3). Like OrderEntity, this is the "database shape" of a pending event and lives only in the
// persistence-facing outbox package; the rest of the app deals in the OrderPlacedEvent domain record.
//
// The whole point of this row is DURABILITY of the intent-to-publish: it is INSERTED in the very same
// transaction as the order (see OutboxEventWriter, called from OrderService.placeOrder), so the fact
// "this event must be published" is committed atomically with the order itself. A background relay
// (OutboxRelay) later reads the unprocessed rows, publishes them to Kafka, and flips `processed` + stamps
// `sentAt`. Two fields make redelivery safe: `aggregateId` is the Kafka key (per-order ordering) and
// `eventId` is the event's STABLE identity — it is minted once at write time and re-sent unchanged on every
// relay attempt, so a redelivered event carries the same id and idempotent consumers (Day 32) dedup on it.
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    private String id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    // The event serialized as JSON. Stored verbatim so the relay re-sends the EXACT bytes that were
    // recorded in the order's transaction — a redelivery is byte-for-byte identical, not a re-derivation.
    @Column(nullable = false, length = 4000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // The SENT flag. false = still awaiting the relay; true = successfully published to Kafka.
    @Column(nullable = false)
    private boolean processed;

    @Column(name = "sent_at")
    private Instant sentAt;

    // JPA requires a no-arg constructor to instantiate rows on load.
    protected OutboxEvent() {
    }

    private OutboxEvent(String id, String aggregateType, String aggregateId, String eventId,
                        String eventType, String topic, String payload, Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
        this.processed = false;
        this.sentAt = null;
    }

    // Factory for a brand-new, not-yet-sent outbox row. Called from the writer inside the order's transaction.
    public static OutboxEvent pending(String id, String aggregateType, String aggregateId, String eventId,
                                      String eventType, String topic, String payload, Instant createdAt) {
        return new OutboxEvent(id, aggregateType, aggregateId, eventId, eventType, topic, payload, createdAt);
    }

    // Mark this row as published. Called by the relay AFTER Kafka has acknowledged the send, so the flag
    // only flips once the event is durably on the broker.
    public void markSent(Instant when) {
        this.processed = true;
        this.sentAt = when;
    }

    public String getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isProcessed() { return processed; }
    public Instant getSentAt() { return sentAt; }
}

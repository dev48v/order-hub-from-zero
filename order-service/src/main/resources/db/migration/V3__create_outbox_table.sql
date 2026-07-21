-- V3: the TRANSACTIONAL OUTBOX table (Day 30).
--
-- The dual-write problem: today placeOrder does two independent writes — it saves the order
-- in the database, then separately publishes an OrderPlaced to Kafka. Those are two systems
-- and there is no shared transaction across them: a crash between the two leaves them diverged
-- (order saved but event lost, or — with a publish-first ordering — an event for an order that
-- was rolled back). The outbox fixes this by turning the second write into a FIRST-write: the
-- event is inserted into this table IN THE SAME DB TRANSACTION as the order, so state and the
-- intent-to-publish commit atomically — either both land or neither does. A separate relay
-- (poller) then reads the unsent rows and publishes them to Kafka, marking each sent on ack.
--
-- Portable SQL only (VARCHAR / BOOLEAN / TIMESTAMP), so the exact same file runs on H2 locally
-- and PostgreSQL in production — the same rule V1/V2 followed.
CREATE TABLE outbox_event (
    id             VARCHAR(36)   PRIMARY KEY,   -- this outbox row's own id (UUID)
    aggregate_type VARCHAR(64)   NOT NULL,      -- the aggregate the event is about, e.g. 'Order'
    aggregate_id   VARCHAR(64)   NOT NULL,      -- the order id — ALSO used as the Kafka message key
    event_id       VARCHAR(64)   NOT NULL,      -- the domain event's STABLE id (survives redelivery → dedup key)
    event_type     VARCHAR(64)   NOT NULL,      -- 'OrderPlaced'
    topic          VARCHAR(128)  NOT NULL,      -- destination Kafka topic
    payload        VARCHAR(4000) NOT NULL,      -- the event serialized as JSON (small; 4000 is ample headroom)
    created_at     TIMESTAMP     NOT NULL,      -- when the row was written (inside the order's tx)
    processed      BOOLEAN       NOT NULL DEFAULT FALSE, -- the SENT flag: false = awaiting relay, true = published
    sent_at        TIMESTAMP                    -- when the relay published it (null until sent)
);

-- The relay's hot query is "the oldest unsent rows first", so index exactly that access path.
CREATE INDEX idx_outbox_unprocessed ON outbox_event (processed, created_at);

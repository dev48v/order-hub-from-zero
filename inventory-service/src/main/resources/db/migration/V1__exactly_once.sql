-- V1 (inventory-service) — the EXACTLY-ONCE consumer's schema (Day 32).
--
-- Two tables own the whole "process each event exactly once, even after a crash" story:
--
--   stock_levels     — the PERSISTENT stock position. Day 17 kept stock in a ConcurrentHashMap (fine for
--                      teaching the service split, but a restart wipes it and nothing is transactional). The
--                      exactly-once path needs the business effect (the reservation) to be a DB write, so it
--                      can commit in the SAME transaction as the dedup marker below. Seeded with the exact
--                      same starter catalogue the in-memory repository uses, so both paths behave alike.
--
--   processed_events — the DEDUP store. One row per Kafka record we have FULLY processed, keyed by the
--                      record's physical coordinates (topic, partition, offset). Kafka guarantees only
--                      AT-LEAST-ONCE delivery to a consumer: if the app crashes AFTER committing its DB work
--                      but BEFORE its offset is committed, Kafka redelivers the SAME record (same offset) on
--                      restart. Because the marker was written in the same transaction as the reservation, a
--                      redelivery finds the marker already present and SKIPS the business effect — so stock is
--                      reserved exactly once end to end. The marker insert + the reservation are atomic: either
--                      both commit or neither does, which is what makes the pattern correct.
--
-- Portable SQL only (VARCHAR / INT / BIGINT / TIMESTAMP), so the identical file runs on H2 (local/tests) and
-- PostgreSQL (prod) — the same rule order-service's migrations follow. Note the column names partition_id /
-- kafka_offset: bare `offset` (and `partition`) are reserved-ish words in several engines, so we spell them
-- out to stay portable.

CREATE TABLE stock_levels (
    sku       VARCHAR(64)  PRIMARY KEY,   -- the stock-keeping unit — the stable product identifier
    name      VARCHAR(128) NOT NULL,      -- human-readable product name
    available INT          NOT NULL       -- units on hand that can still be reserved
);

-- The starter catalogue — identical SKUs/quantities to InMemoryStockRepository so the persistent
-- exactly-once path starts from the same stock as the in-memory Day-26 path.
INSERT INTO stock_levels (sku, name, available) VALUES
    ('KEYBOARD-001', 'Mechanical keyboard', 42),
    ('MOUSE-001',    'Wireless mouse',       30),
    ('HUB-001',      'USB-C hub',            15),
    ('MONITOR-4K',   '4K monitor',            7),
    ('WEBCAM-001',   'Webcam',               12),
    ('STAND-001',    'Laptop stand',          0);   -- deliberately out of stock for demos

CREATE TABLE processed_events (
    topic        VARCHAR(128) NOT NULL,   -- the source topic the record came from
    partition_id INT          NOT NULL,   -- the partition within that topic
    kafka_offset BIGINT       NOT NULL,   -- the record's offset — (topic, partition, offset) is globally unique
    event_id     VARCHAR(64),             -- the domain event's stable id (audit / cross-check)
    order_id     VARCHAR(64),             -- which order this record was about (audit)
    outcome      VARCHAR(32)  NOT NULL,   -- what we did: RESERVED / INSUFFICIENT_STOCK / UNKNOWN_SKU
    processed_at TIMESTAMP    NOT NULL,   -- when the record was processed (inside the reservation's tx)
    -- The dedup key. The PRIMARY KEY does double duty: it is the identity we look up on every delivery, and
    -- its UNIQUENESS is the last line of defence — even under a race, a second insert for the same record
    -- fails, so the business effect can never be applied twice.
    CONSTRAINT pk_processed_events PRIMARY KEY (topic, partition_id, kafka_offset)
);

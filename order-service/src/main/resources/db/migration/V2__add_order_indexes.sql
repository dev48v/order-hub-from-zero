-- V2: index the columns we filter and sort orders by.
-- A forward migration layered on top of V1 to show versioned schema evolution:
-- Flyway applies V2 after V1, never re-runs V1. Plain CREATE INDEX is portable
-- across both H2 (local) and PostgreSQL (prod).

CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created_at ON orders(created_at);

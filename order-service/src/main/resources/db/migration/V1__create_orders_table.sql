-- V1: initial schema for OrderHub.
-- Creates the "orders" table that backs OrderEntity (Day 2). Column names match
-- Hibernate's default snake_case mapping (createdAt -> created_at). Uses only
-- portable SQL (VARCHAR / INT / TIMESTAMP / PRIMARY KEY) so the exact same file
-- runs on H2 locally and PostgreSQL in production.

CREATE TABLE orders (
    id         VARCHAR(36)  PRIMARY KEY,
    customer   VARCHAR(255) NOT NULL,
    item       VARCHAR(255) NOT NULL,
    quantity   INT          NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP    NOT NULL
);

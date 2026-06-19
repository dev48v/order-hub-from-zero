# OrderHub — Production-Grade Spring Boot, Day by Day

Building a **real, production-grade** e-commerce order-fulfillment backend with Spring Boot — **one production concern shipped every day**. Not toy demos: validation, resilience, Redis, Kafka, sagas, security, tracing, Kubernetes — the things real systems actually need.

Read it in order: `git log --reverse` walks the whole build, one concept per commit.
Full plan: see [ROADMAP.md](ROADMAP.md) (Day 1 → 50).

---

## Day 1 — Order REST API + layered architecture

A clean Spring Boot REST API for orders, with the classic layered structure every production service uses. Zero infrastructure required today (in-memory store) — Day 2 swaps in PostgreSQL without touching the layers above the repository.

### Run it

```bash
mvn spring-boot:run
# server on http://localhost:8080
```

### Try it

```bash
# place an order  → 201 Created
curl -s -X POST localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customer":"devanshu","item":"Mechanical Keyboard","quantity":2}'

# list all orders
curl -s localhost:8080/api/orders

# fetch one        → 404 if the id is unknown
curl -s localhost:8080/api/orders/{id}

# confirm an order → 409-style rule: only a PLACED order can be confirmed
curl -s -X POST localhost:8080/api/orders/{id}/confirm

# validation: a bad body is rejected with 400 automatically
curl -s -X POST localhost:8080/api/orders \
  -H "Content-Type: application/json" -d '{"customer":"","quantity":-1}'
```

### The layered architecture

```
HTTP → Controller → Service → Repository → (store)
        thin        rules      interface
```

| Layer | File | Job |
|-------|------|-----|
| Controller | `web/OrderController` | HTTP only — map requests, validate (`@Valid`), set status codes |
| DTOs | `web/dto/*` | the API contract, separate from the domain |
| Service | `service/OrderService` | business logic + orchestration |
| Repository | `repository/OrderRepository` (interface) | storage abstraction — swappable |
| Domain | `domain/Order` | the model + the rules it owns |

**Why it matters:** the controller doesn't know how orders are stored; the repository doesn't know about HTTP. Each layer has one job, so on Day 2 we replace the in-memory repository with JPA + Postgres and nothing else changes. That separation is what makes a codebase survive 50 days of new features.

---

Part of the OrderHub series — a production Spring Boot backend built one feature a day.

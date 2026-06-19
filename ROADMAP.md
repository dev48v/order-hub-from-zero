# OrderHub — Production-Grade, Day by Day

> Build a real, production-grade Spring Boot backend — one production concern shipped every day. Not toy demos: validation, resilience, Kafka, tracing, security, Kubernetes — the things real systems actually need.

Its own series (own Day count, Day 1 → 50). One production-grade Spring Boot backend, growing one feature per day. Domain: e-commerce **order fulfillment** (Orders → Inventory → Payments → Shipping → Notifications). Starts a clean monolith, becomes event-driven microservices with full observability and a Kubernetes deploy.

**Format:** each day = one feature, one step-by-step commit, one 3-tier learning page (👁 LOOK / 🧠 UNDERSTAND / 🔨 BUILD) + a dev.to article. **LinkedIn = weekly milestone**, not daily.

---

## Phase 1 — Rock-solid monolith (Days 1–10)
- **1.** Order REST API + layered architecture (Controller → Service → Repository, in-memory)
- **2.** Persistence: JPA + PostgreSQL
- **3.** Database migrations with Flyway
- **4.** DTOs + Bean Validation (request/response separation)
- **5.** Global exception handling (`@ControllerAdvice` + RFC-7807 ProblemDetail)
- **6.** Pagination, sorting & filtering on list endpoints
- **7.** Configuration & profiles (`@ConfigurationProperties`, dev/prod)
- **8.** Unit + slice tests (JUnit 5, Mockito, MockMvc)
- **9.** Integration tests with Testcontainers (real Postgres)
- **10.** OpenAPI/Swagger docs + Dockerfile

## Phase 2 — Performance & resilience (Days 11–16)
- **11.** Redis caching (`@Cacheable` / `@CacheEvict`)
- **12.** Cache strategies (TTL, cache-aside, eviction)
- **13.** Rate limiting (Bucket4j + Redis)
- **14.** Resilience4j circuit breaker
- **15.** Resilience4j retry + timeout + bulkhead
- **16.** Idempotency keys (safe POST retries)

## Phase 3 — Break into microservices (Days 17–24)
- **17.** Extract the Inventory service (monorepo modules, 2nd Spring Boot app)
- **18.** Synchronous service-to-service calls with OpenFeign
- **19.** Service discovery with Eureka
- **20.** API Gateway (Spring Cloud Gateway)
- **21.** Centralized config (Spring Cloud Config Server)
- **22.** Client-side load balancing (Spring Cloud LoadBalancer)
- **23.** Config refresh + secrets management
- **24.** Inter-service authentication (service tokens)

## Phase 4 — Event-driven with Kafka (Days 25–33)
- **25.** Kafka setup + first producer (`OrderPlaced` event)
- **26.** Kafka consumer (Inventory reserves stock on event)
- **27.** Payment service (event-driven)
- **28.** Choreography saga (order → inventory → payment → ship)
- **29.** Orchestration saga (a saga coordinator)
- **30.** Transactional outbox pattern
- **31.** Dead-letter topics + retry/backoff
- **32.** Idempotent / exactly-once consumers
- **33.** Schema registry + Avro (event contracts & versioning)

## Phase 5 — Security & observability (Days 34–40)
- **34.** Spring Security basics + password auth
- **35.** JWT issue + validate
- **36.** OAuth2 / OIDC (resource server)
- **37.** Method-level security + roles/scopes
- **38.** Actuator health / readiness / liveness probes
- **39.** Metrics → Micrometer + Prometheus + Grafana dashboard
- **40.** Distributed tracing (Micrometer Tracing + Tempo/Zipkin) + correlation IDs + centralized logging (Loki)

## Phase 6 — Production & deploy (Days 41–50)
- **41.** Notification service (email/SMS, event-driven)
- **42.** Dockerize every service + `docker-compose` for the full stack
- **43.** Kubernetes manifests (Deployments, Services, ConfigMaps, Secrets)
- **44.** Helm chart + Ingress
- **45.** CI/CD pipeline (build → test → image → deploy)
- **46.** DB migrations in CI + zero-downtime / rolling deploys
- **47.** Load & performance testing (k6 / Gatling) + tuning
- **48.** Chaos / resilience testing
- **49.** Spring AI feature — natural-language order search / AI assistant over orders
- **50.** 🏁 Capstone: full end-to-end demo + architecture write-up

---

**Repo:** single monorepo `order-hub-from-zero` (services become modules from Phase 3). Signature colour: **Spring green**. Runs as its own series, separate from the finished 50-day TechFromZero; the game/ml/dl/ai sub-series continue unchanged.

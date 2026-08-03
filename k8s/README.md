# OrderHub on Kubernetes — Day 43 (Phase 6 · DEPLOY)

Day 42 turned every service into a container and stood the whole system up with one
`docker compose up`. Day 43 takes that **same image set** to **Kubernetes**: plain,
vendor-neutral YAML manifests — a `Deployment` + `Service` per app, a shared `ConfigMap`
and a `Secret`, and **Actuator-backed liveness / readiness / startup probes** that gate
every rollout.

> These are **hand-written manifests** on purpose — so you can read exactly what a Helm
> chart would generate for you. The Helm chart + Ingress come **Day 44**.

---

## What's in here

| File | Kind(s) | Purpose |
|------|---------|---------|
| `00-namespace.yaml` | Namespace | `orderhub` — the tenant everything lives in. |
| `10-configmap.yaml` | ConfigMap | Shared **non-secret** config (profiles, service DNS, broker/cache/db addresses). |
| `11-secret.yaml` | Secret | **Placeholder** JWT key, inter-service token, DB creds (base64 — *not real values*). |
| `20-infra-postgres.yaml` | Deployment + Service | PostgreSQL (lightweight, ephemeral). |
| `21-infra-redis.yaml` | Deployment + Service | Redis (lightweight, ephemeral). |
| `22-infra-kafka.yaml` | Deployment + Service | Kafka KRaft single node (lightweight, ephemeral). |
| `30-config-server.yaml` | Deployment + Service | Config server (`orderhub/config-server:0.1.0`, :8888). |
| `31-eureka-server.yaml` | Deployment + Service | Eureka registry (`:0.1.0`, :8761). |
| `32-api-gateway.yaml` | Deployment + Service | API gateway (`:0.1.0`, :8080). |
| `33-order-service.yaml` | Deployment + Service | Order service (`:0.1.0`, :8082). |
| `34-inventory-service.yaml` | Deployment + Service | Inventory service (`:0.1.0`, :8081). |
| `35-payment-service.yaml` | Deployment + Service | Payment service (`:0.1.0`, :8083). |
| `36-shipping-service.yaml` | Deployment + Service | Shipping service (`:0.1.0`, :8084). |
| `37-notification-service.yaml` | Deployment + Service | Notification service (`:0.1.0`, :8085). |

Image names + container ports are **exactly** the Day-42 Docker build (`orderhub/<svc>:0.1.0`).
All app Services are `ClusterIP` — internal only; external traffic is a Day-44 Ingress concern.

---

## Apply

The numeric filename prefixes encode the apply order (namespace → config/secret → infra →
services), but Kubernetes is declarative and self-healing, so a single recursive apply is
all you need — the control plane reconciles until everything is Running regardless of order:

```bash
# 1. Build the images (Day 42) so the cluster can find them, e.g. for kind:
docker compose build
kind load docker-image orderhub/config-server:0.1.0 orderhub/eureka-server:0.1.0 \
  orderhub/api-gateway:0.1.0 orderhub/order-service:0.1.0 orderhub/inventory-service:0.1.0 \
  orderhub/payment-service:0.1.0 orderhub/shipping-service:0.1.0 orderhub/notification-service:0.1.0

# 2. Apply the whole tree.
kubectl apply -f k8s/

# 3. Watch it converge.
kubectl -n orderhub get pods -w

# 4. Reach the gateway (ClusterIP → local port).
kubectl -n orderhub port-forward svc/api-gateway 8080:8080
```

Tear the whole thing down with one command:

```bash
kubectl delete namespace orderhub
```

> **Replace the secrets first.** `11-secret.yaml` ships **base64 placeholders only** — base64
> is encoding, not encryption. In a real cluster, create the Secret out-of-band
> (`kubectl -n orderhub create secret generic orderhub-secrets --from-literal=JWT_SECRET=…`)
> or manage it with Sealed Secrets / External Secrets / SOPS / Vault, and never commit real values.

---

## How the probes gate the rollout

Every Spring Boot service exposes **three** probes, wired to **Actuator health groups**:

| Probe | Endpoint | What Kubernetes does with it |
|-------|----------|------------------------------|
| **startup** | `/actuator/health/liveness` | Holds the liveness/readiness clocks until a slow JVM has *started* (up to 30 × 10s). No premature restarts. |
| **readiness** | `/actuator/health/readiness` | Gates **traffic** — a pod is removed from its Service's endpoints (and from a rolling deploy's "available" count) until it reports `readinessState = UP`. |
| **liveness** | `/actuator/health/liveness` | Gates **restarts** — a wedged pod that stops reporting `livenessState = UP` is killed and recreated. |

Because the **readiness** probe feeds the Deployment's rolling-update strategy, a new
ReplicaSet's pods only start taking traffic once Actuator says they're ready, and old pods
stay in rotation until then — that's the **zero-drop rollout** (fully realised Day 46).

### The gated Spring config

Each service's `application.yml` gained a **profile-gated document** (Day 43) that only
activates when `k8s` is among the active profiles (the ConfigMap sets `SPRING_PROFILES_ACTIVE=k8s`):

```yaml
---
spring:
  config:
    activate:
      on-profile: k8s
management:
  endpoint:
    health:
      probes:
        enabled: true          # expose /actuator/health/{liveness,readiness}
      group:
        readiness:
          include: readinessState
        liveness:
          include: livenessState
```

Gating it behind the `k8s` profile means **local, test, and `docker compose` runs are
byte-for-byte unchanged** — they never set that profile, so the probe endpoints stay off
there exactly as before. `eureka-server` additionally gained `spring-boot-starter-actuator`
on Day 43 so it, too, honours the same liveness/readiness contract.

---

## Config vs. Secret

- **`orderhub-config` (ConfigMap)** — plaintext addresses + switches, injected into every app
  container with `envFrom`. This is the Kubernetes home of the per-service `environment:` blocks
  from `docker-compose.yml`, declared once.
- **`orderhub-secrets` (Secret)** — the sensitive keys, consumed two ways: `envFrom` for the app
  containers (Spring relaxed-binds `SPRING_DATASOURCE_USERNAME`, `JWT_SECRET`, … onto its
  properties), and `secretKeyRef` for the postgres pod (seeds `POSTGRES_USER` / `POSTGRES_PASSWORD`
  from the same keys, so both sides agree without hardcoding).

Services needing a second profile override `SPRING_PROFILES_ACTIVE` with an explicit `env:`
entry (which wins over the ConfigMap): `config-server` → `native,k8s`, `api-gateway` and
`order-service` → `prod,k8s`.

---

## Production notes (deliberately out of scope for a demo)

- **Stateful infra** (Postgres, Redis, Kafka) is here as **lightweight, ephemeral Deployments**
  so `kubectl apply -f k8s/` runs anywhere. Real clusters use **StatefulSets + PersistentVolumes**,
  an **operator** (CloudNativePG, Strimzi), or **managed services** (RDS, MSK) reached by the same
  Service names.
- **External exposure** (Ingress / TLS) and a **Helm chart** are **Day 44**.
- **HorizontalPodAutoscaler**, PodDisruptionBudgets, and NetworkPolicies layer on later.

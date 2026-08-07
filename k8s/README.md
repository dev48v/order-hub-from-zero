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

## Zero-downtime rollout (Day 43 → **Day 46**)

Day 43 gated the rollout on **readiness**. **Day 46** closes the remaining gaps so a
`kubectl apply` of a new image tag serves **every request without a drop** — on both sides of
the roll (the *new* pod isn't sent traffic too early, the *old* pod isn't killed mid-request):

| Field (on every app Deployment) | Value | What it buys |
|---|---|---|
| `strategy.type` | `RollingUpdate` | Replace pods incrementally, never all at once. |
| `strategy.rollingUpdate.maxUnavailable` | **`0`** | Never drop below the desired replica count — a new pod must be **Ready before** an old one is retired. |
| `strategy.rollingUpdate.maxSurge` | `1` | Add one extra pod first — so even a **single-replica** service rolls over with no gap. |
| `minReadySeconds` | `10` | A new pod counts as *available* only after it stays Ready this long (rides out a flapping probe). |
| `lifecycle.preStop` | `sleep 10` | On termination, sleep so the pod's **removal from the Service endpoints propagates** before the app gets SIGTERM — no new request lands on a stopping pod. |
| `terminationGracePeriodSeconds` | `45` | Total SIGTERM→SIGKILL budget; covers the preStop sleep **plus** the app's graceful shutdown. |
| `revisionHistoryLimit` | `5` | Keep old ReplicaSets so `kubectl rollout undo` has somewhere to roll back to. |

On the app side, each service's **k8s-profile** config turns on Spring Boot **graceful shutdown**
(`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`), so on SIGTERM
the app stops accepting new requests but **finishes the in-flight ones** before the context
closes. Because the k8s probes are on, Boot also flips **readiness → `OUT_OF_SERVICE`** for the
whole shutdown window, so the platform stops routing immediately.

**The termination order that makes it drop-free:** pod marked *Terminating* → removed from Service
endpoints **and** `preStop` sleep start (in parallel) → sleep ends → **SIGTERM** → Spring drains
in-flight requests (≤ 30s) → process exits (or SIGKILL at 45s). Since `10 (sleep) + 30 (drain) < 45`,
nothing is ever cut off.

### Watch a rollout / roll it back

```bash
# Trigger a roll (new image tag, or any pod-template change):
kubectl -n orderhub set image deployment/order-service order-service=orderhub/order-service:0.2.0

# Watch it converge — blocks until the new ReplicaSet is fully available (or fails):
kubectl -n orderhub rollout status deployment/order-service

# Inspect history (revisionHistoryLimit keeps the last 5):
kubectl -n orderhub rollout history deployment/order-service

# Roll straight back to the previous revision if the new one misbehaves:
kubectl -n orderhub rollout undo deployment/order-service
# ...or to a specific revision:
kubectl -n orderhub rollout undo deployment/order-service --to-revision=3

# Pause / resume a roll to canary it:
kubectl -n orderhub rollout pause  deployment/order-service
kubectl -n orderhub rollout resume deployment/order-service
```

> Infra Deployments (Postgres/Redis/Kafka) keep the default strategy on purpose — a single-replica
> stateful pod on a ReadWriteOnce volume can't surge a second pod, so zero-downtime there is a
> StatefulSet + PDB concern, out of scope for these ephemeral demo pods.

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

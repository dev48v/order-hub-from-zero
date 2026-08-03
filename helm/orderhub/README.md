# OrderHub Helm chart — Day 44 (Phase 6 · DEPLOY)

Day 43 hand-wrote fourteen flat YAML manifests under [`k8s/`](../../k8s) — a `Deployment`
+ `Service` per app, a shared `ConfigMap` + `Secret`, and Actuator liveness/readiness/startup
probes. Day 44 **templatizes** that exact topology into a Helm chart and adds the piece Day 43
deliberately deferred: an **Ingress** as the external front door.

The win of the chart is **one shape + data**: the eight near-identical Day-43 Deployments (and
their Services) collapse into a single `deployment.yaml` / `service.yaml` that `range`s over a
`services:` map in [`values.yaml`](values.yaml). Retuning replicas, images, or routes is now a
value override — no YAML surgery, no `kubectl` edits.

```
helm/orderhub/
├── Chart.yaml            # chart metadata (apiVersion v2, name/version/appVersion)
├── values.yaml           # the DATA: 8 services, config, secrets, ingress, global, probes
├── README.md             # this file
└── templates/            # the SHAPE (Go templating rendered by helm)
    ├── _helpers.tpl      # reusable label / selector / image named-templates
    ├── configmap.yaml    # shared non-secret config  (was k8s/10-configmap.yaml)
    ├── secret.yaml       # placeholder secrets        (was k8s/11-secret.yaml)
    ├── deployment.yaml   # ONE template -> all 8 Deployments (range .Values.services)
    ├── service.yaml      # ONE template -> all 8 ClusterIP Services
    ├── ingress.yaml      # NEW: host + path routing + gateway default backend
    └── NOTES.txt         # post-install hints (printed after `helm install`)
```

## Install

```bash
# 1. Build the images (Day 42) and load them into your local cluster, e.g. kind:
docker compose build
kind load docker-image orderhub/config-server:0.1.0 orderhub/eureka-server:0.1.0 \
  orderhub/api-gateway:0.1.0 orderhub/order-service:0.1.0 orderhub/inventory-service:0.1.0 \
  orderhub/payment-service:0.1.0 orderhub/shipping-service:0.1.0 orderhub/notification-service:0.1.0

# 2. Install the chart into the orderhub namespace.
helm install orderhub ./helm/orderhub -n orderhub --create-namespace

# 3. Watch it converge (readiness probes gate traffic).
kubectl -n orderhub get pods -w
```

Preview exactly what will be applied — without a cluster — with `helm template`:

```bash
helm template orderhub ./helm/orderhub | less
helm lint ./helm/orderhub
```

Upgrade in place after changing a value, roll back if needed, and uninstall:

```bash
helm upgrade orderhub ./helm/orderhub -n orderhub
helm rollback orderhub 1 -n orderhub
helm uninstall orderhub -n orderhub
```

## Enable / disable the Ingress

The Ingress is **on** by default (`ingress.enabled: true`), routing one host to the API-facing
services with the **gateway as the default backend**:

| Path (Prefix) | Service |
|---|---|
| `/api/orders` | order-service |
| `/api/inventory` | inventory-service |
| `/api/payments` | payment-service |
| `/api/shipments` | shipping-service |
| *(everything else)* | **api-gateway** (default backend) |

It needs an ingress controller (ingress-nginx). On kind/minikube enable the nginx addon, then
point the host at it:

```bash
echo "127.0.0.1 orderhub.local" | sudo tee -a /etc/hosts
curl http://orderhub.local/api/orders
```

Turn it off (fall back to port-forwarding the gateway) with a single override:

```bash
helm upgrade orderhub ./helm/orderhub --set ingress.enabled=false
kubectl -n orderhub port-forward svc/api-gateway 8080:8080
```

## Common value overrides

Everything Day 43 hard-coded is now a value. A few worth knowing:

```bash
# Scale a service.
helm upgrade orderhub ./helm/orderhub --set services.order-service.replicas=4

# Push images from a registry instead of local :0.1.0 tags.
helm upgrade orderhub ./helm/orderhub \
  --set global.imageRegistry=ghcr.io/dev48v --set global.imagePullPolicy=Always

# Change the ingress host / class.
helm upgrade orderhub ./helm/orderhub \
  --set ingress.host=orders.example.com --set ingress.className=traefik

# Supply the Secret out-of-band (do NOT ship real secrets in values.yaml).
kubectl -n orderhub create secret generic orderhub-secrets \
  --from-literal=JWT_SECRET=… --from-literal=SERVICE_TOKEN=… \
  --from-literal=SPRING_DATASOURCE_USERNAME=… --from-literal=SPRING_DATASOURCE_PASSWORD=…
helm upgrade orderhub ./helm/orderhub --set secrets.create=false
```

Larger edits (extra config keys, new services, different resources) go in a `-f my-values.yaml`
overlay so the base chart stays pristine.

## `values.yaml` reference

| Key | Purpose |
|---|---|
| `global.imageRegistry` / `global.imagePullPolicy` | Optional registry prefix + pull policy for every image. |
| `probes.{startup,readiness,liveness}` | The Actuator health-group probe timings, shared by all 8 apps. |
| `config.name` / `config.data` | The shared `orderhub-config` ConfigMap (`envFrom` into every app). |
| `config.configImport` | `SPRING_CONFIG_IMPORT` value, stamped on services flagged `configImport: true`. |
| `secrets.name` / `secrets.data` / `secrets.create` | The `orderhub-secrets` Secret — **placeholders only**; set `create: false` to bring your own. |
| `services.<name>` | Per-app `image`/`tag`/`port`/`replicas`/`component`/`profile`/`configImport`/`serverPort`/`resources`. |
| `ingress.enabled` / `className` / `host` / `defaultBackend` / `routes` | The external front door. |

> ⚠️ **Secrets are placeholders.** `values.yaml` ships base64-encoded `change-me-…` strings so
> the chart renders a complete Secret. base64 is encoding, not encryption — never commit real
> values. Manage production secrets with Sealed Secrets / External Secrets / SOPS / Vault and set
> `secrets.create: false`.

## Where infra lives

Postgres, Redis and Kafka were **lightweight, ephemeral Deployments** in Day 43 so `kubectl
apply -f k8s/` ran anywhere. This chart templatizes the eight *application* services (the moving
parts you tune per environment); real clusters run the stateful infra as StatefulSets/operators
or managed services (RDS/MSK), reached by the same `postgres` / `redis` / `kafka` Service names
the ConfigMap already points at. Point `config.data` at those addresses and deploy the apps alone.

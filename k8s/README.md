# The portal on Kubernetes (k3s/k3d) — dev-parity manifests

Kustomize manifests for the whole portal product **plus the minimal identity
core it cannot live without**: security, email, the stub IdP, Kafka (single-node
KRaft) with the `kafka-topics-init` Job that gives the mail DLQ ledger its
compaction, security's Postgres and Mailpit. Everything in one `portal` namespace —
the k8s mirror of the single compose project. Validated end to end on a local
k3d cluster (all pods Ready; registration → Mailpit → verify → authenticate →
upload → comment → favourite → account-deletion saga, all green).

```
k8s/
├── base/                 # one file per component: Deployment+Service (+PVC)
│   ├── kustomization.yaml
│   └── ...
└── overlays/
    └── dev/              # local k3d: dev Secrets, imagePullPolicy Never
        └── kustomization.yaml
```

## Bring it up on k3d

```bash
# 1. images — compose builds them (project name "security"):
./infra-up.sh            # or: docker compose build

# 2. cluster with the Traefik loadbalancer published on host port 9080
k3d cluster create portal-dev --port 9080:80@loadbalancer

# 3. hand the locally built images to the cluster (nothing is pulled — the
#    dev overlay pins imagePullPolicy: Never)
k3d image import -c portal-dev \
  security-security:latest security-email:latest security-idp:latest \
  security-memes:latest security-comments:latest \
  security-user-collections:latest security-collections-ui:latest \
  security-offboarding:latest security-image-encoder:latest \
  postgres:16-alpine minio/minio:RELEASE.2024-06-13T22-53-53Z \
  apache/kafka:3.9.1 axllent/mailpit:latest

# 4. apply — ALWAYS through the overlay (the base has no Secrets)
kubectl apply -k k8s/overlays/dev
kubectl -n portal get pods -w     # ~2 min until everything is Ready

# tear-down when done looking
k3d cluster delete portal-dev
```

Early restarts of the JVM pods while Postgres/Kafka come up are normal — k8s
has no `depends_on`; the probes gate traffic and the restarts converge.

## Who answers where

Ingress (Traefik, k3s' default class) routes by host name; `*.localhost`
resolves to 127.0.0.1 in browsers and on systemd-resolved machines:

| URL (host port 9080)                        | Service          | compose port |
|---------------------------------------------|------------------|--------------|
| http://memes.portal.localhost:9080          | memes (gallery)  | 8083 |
| http://comments.portal.localhost:9080       | comments         | 8085 |
| http://collections.portal.localhost:9080    | collections-ui   | 8093 |
| http://security.portal.localhost:9080       | security         | 8080 |

Everything else is cluster-internal on its compose port (image-encoder 8087,
user-collections 8092, offboarding 8094, MinIO 9000, Kafka 9092, Mailpit
8025/1025, each Postgres 5432). Peek at internals with a port-forward, e.g.:

```bash
# host port 8026 on purpose (same example as base/mailpit.yaml): when the
# compose stack runs on this machine, IT already owns host port 8025
kubectl -n portal port-forward svc/mailpit 8026:8025   # the "inbox" UI
kubectl -n portal port-forward svc/user-collections 8092:8092
```

## Secrets

Dev values are **generated in the overlay** (`overlays/dev/kustomization.yaml`)
with the same plaintext defaults compose uses: `secret` (all Postgres),
`memes`/`supersecret` (MinIO), `local-dev-key` (mail API), `demo-secret` (IdP
client). Acceptable for a throwaway local cluster only — a hosted overlay
(HOSTING-K3S.md) must bring SealedSecrets/ExternalSecrets instead of literals.

## Two things the browser decides, and neither is baked into an image

Both were found on 2026-07-29, and both fail the same way: the page loads, every
probe stays green, and nobody can sign in.

- **Where the browser calls** — memes-ui reads `import.meta.env.VITE_*`, which
  Vite substitutes at BUILD time, so the jar (and the image carrying it) had
  compose's `localhost:8080` in it permanently. `microservice-memes` now serves
  `/ui-config.js` (`UiConfigController`), a classic script `index.html` loads
  before the module bundle, and `base/memes.yaml` sets `MEMES_UI_*` to the
  ingress host names. Unset, it answers with compose's addresses, so nothing
  about a compose run changes.
- **Where each service accepts a call FROM** — CORS is judged on the Origin the
  browser reports, and there are THREE surfaces, not one. `SECURITY_CORS_ORIGINS`
  (`base/security.yaml`), `UI_ORIGIN` (`base/comments.yaml`, for the thread under
  a meme) and `COLLECTIONS_ALLOWED_ORIGINS` (`base/user-collections.yaml`, for the
  gallery's star button as well as the favourites UI). All three defaulted to
  compose's localhost ports and none were set here, so sign-in, comments and
  favourites would each have died the same silent death. security additionally
  needs `allow-credentials: true`, now explicit, because Micronaut 5 flipped that
  default and every sign-in travels with `credentials: 'include'`.

**These must agree, pairwise.** One side names where the browser calls, the
other names where that call is accepted from; a mismatch is invisible until a
real browser tries it. `CorsPreflightTest` (security), `CorsOriginsTest`
(comments) and `UiConfigTest` (memes) guard the halves in seconds; the browser
e2e is what proves them together.

## Probes — readiness and liveness are different questions

- **offboarding** and **user-collections**: **readiness on `/health`** (503
  when a loop stops completing passes — broker or database away; the pod
  leaves traffic/gating and comes back when the dependency does) and
  **liveness on `/alive`** (503 only when a loop thread died or stopped being
  scheduled past `*_ALIVE_STALL_SEC` — code default 240s, set explicitly to
  240s in both manifests, and floored at 183s by each service itself (the
  worst legal iteration sums to 146s of Kafka, JDBC and backoff clocks, plus
  a 25% margin) — the one failure a
  restart actually cures). Liveness used to hit `/health` too, which
  restart-looped these pods whenever their Postgres was down; a dead loop
  still gets bounced, an outage no longer does. Exactly what those
  endpoints were built for.
- **memes/comments** (Spring Boot): `/actuator/health/{liveness,readiness}` —
  auto-enabled when Spring detects Kubernetes.
- **security** (Micronaut): `/health`; **email** (Quarkus): TCP only (the image
  ships no health extension — same as compose); Python stubs: TCP or `/health`.
- JVMs get a generous `startupProbe` (36 × 5s = up to 3 min) instead of huge
  initialDelays.

## Env pins the deployment must respect

- **memes**: never set `SPRING_DATASOURCE_HIKARI_AUTO_COMMIT=false`. The
  repo pins autocommit in a test that does not see env overrides, and the
  after-commit sweeps on the DB blob store rely on the connection's
  autocommit being restored — an env override would silently break them
  in production while every test stays green.
- **memes**: `MEMES_DECODE_CONCURRENCY` (default 3) caps concurrent image
  decodes; the memory limit in `base/memes.yaml` is sized for 3 × ~256 MB
  worst-case decodes — raise them together or not at all.

## Deliberately missing vs compose

- **Observability** (Prometheus, Grafana, Tempo, Loki, Promtail, exporters) —
  a future overlay of its own.
- **OTel javaagent** — compose attaches it via `JAVA_TOOL_OPTIONS`; here there
  is no Tempo to export to, so no `JAVA_TOOL_OPTIONS` is set at all (a
  `-javaagent` pointing at a missing jar would abort every JVM).
- **sms / push stubs** — register/verify/authenticate and the deletion saga
  never touch them; SMS only carries MFA-SMS codes and no fresh-cluster account
  has that factor enrolled. `SECURITY_SMS_URL` is omitted accordingly
  (see `base/security.yaml`).
- **Browser-side social login** — the stub IdP runs (server-side token/userinfo
  calls work) but its `/authorize` form has no Ingress in this scope.
- *(closed 2026-07-29 — collections-ui used to bake its API base URLs at Vite
  build time; ui-config.sh now writes them at container start, like memes-ui's
  /ui-config.js.)*
- **Resources**: modest requests (~2.9 GiB total) and memory limits
  (~7.3 GiB total — memes alone carries 1.5 GiB for its 3-decode burst
  budget) per HOSTING-K3S.md's small-node budget; CPU limits are omitted on
  purpose — throttling JVM startup only makes probes lie.
- **Images**: local compose tags (`security-*:latest`) + `imagePullPolicy:
  Never` in the dev overlay; the hosted setup swaps these for GHCR-pushed tags.

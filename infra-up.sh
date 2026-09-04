#!/bin/bash
# Build the service jars on the host, then start the PORTAL product on the shared identity
# stack (see docker-compose.yml — the identity services ride in via include from ../shared).
set -euo pipefail
cd "$(dirname "$0")"

# contexts: the system is the default; `--observability` adds Prometheus/Grafana/Loki/Tempo/
# cadvisor/node-exporter (a compose profile) and the OTel Java agent in every JVM service —
# ../shared/observability/enable.sh sets both. Any other argument passes through to compose.
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --observability) . ../shared/observability/enable.sh ;;
        *) ARGS+=("$arg") ;;
    esac
done
set -- "${ARGS[@]+"${ARGS[@]}"}"

# build order across workspaces: the shared kernel first (its install feeds ~/.m2 with the
# libraries this reactor depends on AND packages the identity jars the compose build needs),
# then the portal reactor; sms/push/image/idp are Python and need nothing
# `clean`: target/lib only accumulates (see memes-up.sh) — stale jars from a renamed module would ride into the images
(cd ../shared && ./mvnw -q clean install -DskipTests)
./mvnw -q clean package -DskipTests

docker compose up --build -d "$@"
echo
echo "security  -> http://localhost:8080    email -> http://localhost:8082"
echo "memes     -> http://localhost:8083    mail inbox (Mailpit) -> http://localhost:8025"
[ "${COMPOSE_PROFILES:-}" = observability ] && echo "grafana   -> http://localhost:3000    prometheus -> http://localhost:9090" \
    || echo "(no observability — add --observability for Grafana/Prometheus/Tempo and tracing)"
echo "Smoke test (needs the formula world too): ../shared/infra-smoke.sh"

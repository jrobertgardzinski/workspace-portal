#!/bin/bash
# The MEMES world, end to end: gallery (:8083) + comments (:8085) with sign-in (security :8080),
# verification mail (email -> Mailpit :8025), social login (stub IdP :8091), object storage
# (MinIO) and the observability pair (Grafana :3000 / Prometheus :9090). Databases, Kafka and
# the image encoder ride in via depends_on; the identity services come from
# ../shared/docker-compose.identity.yml via include. The formula world stays down —
# ../formula/formula-up.sh.
set -euo pipefail
cd "$(dirname "$0")"

# same guard as infra-up.sh: the compose anchor points JAVA_TOOL_OPTIONS at the mounted OTel
# agent jar, and a -javaagent aimed at a missing file aborts every JVM service. This script
# does not fetch the agent (the memes world starts no Tempo to receive traces — infra-up.sh
# is the one that provisions it), so when the jar is absent just disable the flag; when
# infra-up.sh fetched it earlier, the services come up traced as usual
# `--observability` adds the monitoring context and the OTel agent (see infra-up.sh)
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --observability) . ../shared/observability/enable.sh ;;
        *) ARGS+=("$arg") ;;
    esac
done
set -- "${ARGS[@]+"${ARGS[@]}"}"

# shared kernel first (libs into ~/.m2 + identity jars; voting and purge-rule ride along —
# the gallery and comments vote through one and close accounts by the other), then the memes jars
(cd ../shared && ./mvnw -q -pl microservice-security/security-infrastructure,microservice-email,voting,purge-rule -am install -DskipTests)
./mvnw -q -pl microservice-memes/memes-infrastructure,microservice-comments/comments-infrastructure -am package -DskipTests

OBS=()
[ "${COMPOSE_PROFILES:-}" = observability ] && OBS=(prometheus grafana cadvisor node-exporter)
docker compose up --build -d \
    security email memes comments idp adminer \
    "${OBS[@]+"${OBS[@]}"}" "$@"

echo
echo "memes     -> http://localhost:8083    comments -> http://localhost:8085"
echo "security  -> http://localhost:8080    mail inbox (Mailpit) -> http://localhost:8025"
echo "adminer   -> http://localhost:8088    (SQL console on the security database: postgres / secret / security)"
[ "${COMPOSE_PROFILES:-}" = observability ] && echo "grafana   -> http://localhost:3000    prometheus -> http://localhost:9090" \
    || echo "(no observability — add --observability for Grafana/Prometheus/Tempo and tracing)"
echo "Full-stack smoke (needs the formula world too): ../shared/infra-smoke.sh"

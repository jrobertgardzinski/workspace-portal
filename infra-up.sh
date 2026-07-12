#!/bin/bash
# Build the service jars on the host, then start the PORTAL product on the shared identity
# stack (see docker-compose.yml — the identity services ride in via include from ../shared).
set -euo pipefail
cd "$(dirname "$0")"

# the OpenTelemetry Java agent (traces -> Tempo) is attached to the JVM services via a mounted
# volume, not baked into the images — fetch it once into the SHARED workspace (the identity
# compose owns observability/); gitignored there, ~21 MB
OTEL_AGENT=../shared/observability/otel/opentelemetry-javaagent.jar
if [ ! -f "$OTEL_AGENT" ]; then
    echo "fetching the OpenTelemetry Java agent..."
    mkdir -p ../shared/observability/otel
    curl -sfL -o "$OTEL_AGENT" \
        "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.29.0/opentelemetry-javaagent.jar" \
        || { echo "WARNING: could not fetch the OTel agent; JVM services will start without tracing"; rm -f "$OTEL_AGENT"; }
fi

# build order across workspaces: the shared kernel first (its install feeds ~/.m2 with the
# libraries this reactor depends on AND packages the identity jars the compose build needs),
# then the portal reactor; sms/push/image/idp are Python and need nothing
(cd ../shared && ./mvnw -q install -DskipTests)
./mvnw -q package -DskipTests

docker compose up --build -d "$@"
echo
echo "security  -> http://localhost:8080    email -> http://localhost:8082"
echo "memes     -> http://localhost:8083    mail inbox (Mailpit) -> http://localhost:8025"
echo "grafana   -> http://localhost:3000    prometheus -> http://localhost:9090"
echo "Smoke test (needs the formula world too): ../shared/infra-smoke.sh"

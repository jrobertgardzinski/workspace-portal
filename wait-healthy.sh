#!/bin/bash
# Block until every container of the portal stack is running — and, where docker has an opinion,
# healthy. This is the step infra-up.sh only TELLS you to take ("then wait for every service to
# report healthy") and that every runner then re-invented with its own curl loop.
#
# Why it is needed at all: `docker compose up -d` returns as soon as the containers are STARTED.
# `depends_on: condition: service_healthy` gates the dependants, so the databases, MinIO, the
# broker and security really are healthy by the time it returns — but the JVMs riding on top
# (memes, comments, user-collections, offboarding) are merely launched, and a suite that starts
# probing at that moment reads a cold JVM as a broken service. That is a flaky red with a cause
# nobody can see in the log.
#
# Usage:  ./wait-healthy.sh [timeout-seconds]      (default 600)
# Exit 0  every service the compose project declares is up.
# Exit 1  the timeout ran out — the offenders are NAMED, with their state and the tail of their
#         logs, because "timed out waiting for the stack" sends the reader straight back to the
#         terminal to type the command this script could have typed for them.
set -euo pipefail
cd "$(dirname "$0")"

timeout=${1:-600}
deadline=$(( SECONDS + timeout ))

# The expected roster comes from the merged config, so the services pulled in through `include:`
# from ../shared (security, kafka, mailpit, the whole observability stack) count too — waiting for
# "what compose started" would be satisfied by an empty stack.
mapfile -t expected < <(docker compose config --services | sort)
if [ "${#expected[@]}" -eq 0 ]; then
    echo "FAIL: docker compose config lists no services — wrong directory, or no docker?"
    exit 1
fi

# A service with no healthcheck of its own (grafana, loki, tempo, promtail, prometheus, cadvisor,
# node-exporter) reports an EMPTY health field. For those "running" is the whole truth docker has,
# and demanding "healthy" would wait out the timeout on a perfectly good stack.
#
# And a ONE-SHOT service — kafka-topics-init, which declares the dead-letter topic and stops — is
# ready precisely BECAUSE it is no longer running. Demanding "running" of it waits out the timeout
# on a stack that is completely fine, which is exactly what happened when that service was added on
# 2026-07-29. Exit code 0 means it did its job; any other exit code is a genuine failure and must
# still be reported.
not_ready() {
    local snapshot svc state health exit_code
    # a failure here means docker itself went away mid-wait; treat it as "nothing is ready yet"
    # rather than as "everything is ready", which is what an unguarded empty answer would mean
    # '|' rather than spaces: a service with no healthcheck reports an EMPTY Health, and
    # whitespace-split reading collapses that empty field — the exit code then lands in the health
    # variable and every healthcheck-less service (grafana, loki, prometheus, promtail, tempo,
    # cadvisor, node-exporter) is declared unwell. An explicit separator keeps the columns apart.
    snapshot=$(docker compose ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}' 2>/dev/null || true)
    for svc in "${expected[@]}"; do
        IFS='|' read -r _ state health exit_code <<<"$(grep -m1 -- "^$svc|" <<<"$snapshot" || true)"
        if [ "$state" = "exited" ] && [ "$exit_code" = "0" ]; then
            continue          # a one-shot that finished its work
        fi
        if [ "$state" != "running" ] || { [ -n "$health" ] && [ "$health" != "healthy" ]; }; then
            printf '%s\n' "$svc"
        fi
    done
}

echo "== waiting up to ${timeout}s for ${#expected[@]} services"
announced=""
while :; do
    waiting=$(not_ready | tr '\n' ' ')
    waiting=${waiting% }
    if [ -z "$waiting" ]; then
        echo "== every service is up (${#expected[@]}/${#expected[@]}) after $((SECONDS))s"
        exit 0
    fi
    # only speak when the set CHANGES: a 600s wait printing every 3s buries its own ending
    if [ "$waiting" != "$announced" ]; then
        echo "   [$((SECONDS))s] still waiting for: $waiting"
        announced=$waiting
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo
        echo "FAIL: after ${timeout}s these services are still not up: $waiting"
        for svc in $waiting; do
            echo "---- $svc"
            # the indent is applied here, not in the template: compose trims leading whitespace
            # out of --format, so a template that carried it printed flush left anyway
            docker compose ps -a --format '{{.Service}} {{.State}} {{.Health}} {{.Status}}' \
                "$svc" 2>&1 | sed 's/^/     /' || true
            docker compose logs --no-color --tail 40 "$svc" 2>&1 | sed 's/^/     /' || true
        done
        exit 1
    fi
    sleep 3
done

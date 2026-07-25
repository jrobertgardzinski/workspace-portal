#!/bin/bash
# The container preflight shared by ../e2e-saga.sh and ../e2e-saga-outage.sh — one copy, because
# both runners need exactly the same two answers before they probe anything over HTTP:
#
#   1. is the stack up AT ALL?  If no container of this compose project is running, the HTTP
#      probes would spend a minute discovering what `docker compose ps` says instantly. Say
#      "run ./infra-up.sh" now, not sixty seconds from now.
#   2. is user-collections stopped, and if so, WHY?  @outage stops that container on purpose
#      (participant-outage.feature), and a run killed mid-scenario leaves it stopped — healing
#      that is right, because the aliveness check afterwards should report today's trouble, not
#      yesterday's. But a container that DIED is not a leftover, and starting it silently would
#      hide a crash behind a green suite. So the two cases are told apart and only the first
#      one is quiet: exited(0) is a leftover, anything else (non-zero exit, restart loop) gets
#      a loud warning with the tail of its log. Both are then started anyway — the suite is
#      still worth running, and refusing to start would only trade a hidden crash for a
#      blocked developer.
#
# Reads COLLECTIONS_URL from the environment (default matches the runners').
set -euo pipefail
cd "$(dirname "$0")/.."   # compose file lives at the repo root, whoever invoked us

COLLECTIONS_URL=${COLLECTIONS_URL:-http://localhost:8092}

if ! docker compose ps -aq >/dev/null 2>&1; then
    # no docker, no daemon, no compose file — nothing to preflight; the HTTP probes in the
    # caller will fail with the usual "start it first" message, which is the honest verdict
    echo "== cannot ask docker about the stack (daemon down? wrong directory?) — skipping the container preflight"
    exit 0
fi

running=$(docker compose ps --services --status running 2>/dev/null || true)
if [ -z "$running" ]; then
    echo "FAIL: the portal stack is not running — no container of this compose project is up"
    echo "      start it first: ./infra-up.sh   (then wait for every service to report healthy)"
    exit 1
fi

if grep -qx user-collections <<<"$running"; then
    exit 0
fi

container=$(docker compose ps -aq user-collections 2>/dev/null | head -n 1)
if [ -z "$container" ]; then
    echo "FAIL: user-collections has no container at all — this stack was never fully brought up"
    echo "      start it first: ./infra-up.sh   (then wait for every service to report healthy)"
    exit 1
fi

# status: created|running|paused|restarting|removing|exited|dead
state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}} {{.State.Restarting}}' \
        "$container" 2>/dev/null || echo "unknown 0 false")
status=${state%% *}; rest=${state#* }; exit_code=${rest%% *}; restarting=${rest#* }

if [ "$status" = "exited" ] && [ "$exit_code" = "0" ] && [ "$restarting" = "false" ]; then
    echo "== user-collections is stopped with exit 0 — leftover from an interrupted @outage run; starting it"
else
    echo
    echo "!! WARNING: user-collections is NOT a leftover — it is $status (exit code $exit_code, restarting=$restarting)."
    echo "!! @outage leaves this container stopped with exit 0; this one went down for a reason of its own."
    echo "!! Starting it anyway so the suite can run, but do not read a green run as 'nothing happened here'"
    echo "!! — go find out why it died. Its last words:"
    docker compose logs --no-color --tail 20 user-collections 2>&1 | sed 's/^/!!   /' || true
    echo
fi

docker compose start user-collections >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
    curl -sf --max-time 3 "$COLLECTIONS_URL/health" >/dev/null 2>&1 && exit 0
    sleep 2
done
echo "== user-collections did not answer /health within 60s — the aliveness check below will say so"

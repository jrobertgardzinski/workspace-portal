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
#      one is quiet: a STOPPED container (see the exit codes below) is a leftover, anything
#      else (an application exit code, a restart loop, an OOM kill) gets a loud warning with
#      the tail of its log. Both are then started anyway — the suite is still worth running,
#      and refusing to start would only trade a hidden crash for a blocked developer (unless
#      E2E_STRICT_PREFLIGHT says otherwise; see below).
#
# Reads COLLECTIONS_URL from the environment (default matches the runners'). Set
# E2E_STRICT_PREFLIGHT=1 (CI) to turn the crash warning into a failed run: a container that
# died on its own then ends this script with exit 1 instead of a green suite that hides it.
set -euo pipefail
cd "$(dirname "$0")/.."   # compose file lives at the repo root, whoever invoked us

COLLECTIONS_URL=${COLLECTIONS_URL:-http://localhost:8092}

if ! docker compose ps -aq >/dev/null 2>&1; then
    # no docker, no daemon, no compose file — nothing to preflight; the HTTP probes in the
    # caller will fail with the usual "start it first" message, which is the honest verdict
    echo "== cannot ask docker about the stack (daemon down? wrong directory?) — skipping the container preflight"
    exit 0
fi

# "no services running" and "the question could not be asked" are different answers, and only
# the first one is the developer's problem: an old compose plugin without `--status` fails here,
# and folding that into the empty string would FAIL a perfectly healthy stack.
if ! running=$(docker compose ps --services --status running 2>/dev/null); then
    echo "== 'docker compose ps --status' is not understood here (compose plugin too old?) — skipping the container preflight"
    exit 0
fi
if [ -z "$running" ]; then
    echo "FAIL: the portal stack is not running — no container of this compose project is up"
    echo "      start it first: ./infra-up.sh   (then wait for every service to report healthy)"
    exit 1
fi

if grep -qx user-collections <<<"$running"; then
    exit 0
fi

# `|| true` for the same reason the `--status` query above tolerates a failure: under
# `set -o pipefail` a failing left-hand side
# of the pipe would kill this script on the spot, and the carefully worded message below — the
# whole point of asking — would never be printed. An empty answer must reach the check.
container=$(docker compose ps -aq user-collections 2>/dev/null | head -n 1 || true)
if [ -z "$container" ]; then
    echo "FAIL: user-collections has no container at all — this stack was never fully brought up"
    echo "      start it first: ./infra-up.sh   (then wait for every service to report healthy)"
    exit 1
fi

# status: created|running|paused|restarting|removing|exited|dead
state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}} {{.State.Restarting}} {{.State.OOMKilled}}' \
        "$container" 2>/dev/null || echo "unknown 0 false false")
read -r status exit_code restarting oomkilled <<<"$state"

# Which exit codes mean "somebody stopped this container" rather than "this container died"?
# Docker reports the exit status of PID 1, and PID 1 in these images is the JVM itself (exec
# form in the Dockerfile — no shell wrapper, no init that translates signals). A JVM killed by
# a signal is reported as 128+signal, NOT as 0:
#
#   0    the process returned from main() on its own — a clean shutdown
#   143  128+15, SIGTERM — exactly what `docker compose stop` sends, i.e. what @outage does
#        and what an interrupted @outage run leaves behind. THE normal leftover.
#   137  128+9, SIGKILL — `docker kill`, or `stop` whose 10s grace period ran out on a JVM
#        that was still flushing. Still somebody stopping it, not the service falling over.
#
# The previous version accepted only 0, which no `docker compose stop` of this image can ever
# produce — so EVERY ordinary leftover was announced as a crash, with twenty log lines ending
# in "Shutdown finished". A crash detector that fires on the normal case is a false-alarm
# generator, so 143 and 137 join 0 here. OOMKilled is checked separately and outranks the exit
# code: 137 with OOMKilled=true is the kernel reaping the JVM, which IS a crash worth shouting
# about even though the number looks like a stop.
leftover=false
if [ "$status" = "exited" ] && [ "$restarting" = "false" ] && [ "$oomkilled" != "true" ]; then
    case "$exit_code" in
        0|143|137) leftover=true ;;
    esac
fi

if [ "$leftover" = true ]; then
    echo "== user-collections is stopped (exit $exit_code) — leftover from an interrupted @outage run; starting it"
else
    echo
    echo "!! WARNING: user-collections is NOT a leftover — it is $status (exit code $exit_code, restarting=$restarting, OOMKilled=$oomkilled)."
    echo "!! A stop leaves this container at exit 0, 143 (SIGTERM) or 137 (SIGKILL); this one went down for a reason of its own."
    if [ "$oomkilled" = "true" ]; then   # `if`, not `&&`: a false test would end the script under `set -e`
        echo "!! OOMKilled=true — the kernel reaped it: raise the container's memory limit or the JVM's heap, do not just restart."
    fi
    echo "!! — go find out why it died. Its last words:"
    docker compose logs --no-color --tail 20 user-collections 2>&1 | sed 's/^/!!   /' || true
    echo
    if [ "${E2E_STRICT_PREFLIGHT:-0}" = "1" ]; then
        echo "!! E2E_STRICT_PREFLIGHT=1 — refusing to start it and hide this behind a green suite."
        echo "!! The container is left exactly as it died, for the postmortem."
        exit 1
    fi
    echo "!! Starting it anyway so the suite can run, but do not read a green run as 'nothing happened here'"
    echo "!! (set E2E_STRICT_PREFLIGHT=1 to make this a failed run instead)."
    echo
fi

docker compose start user-collections >/dev/null 2>&1 || true
for _ in $(seq 1 30); do
    curl -sf --max-time 3 "$COLLECTIONS_URL/health" >/dev/null 2>&1 && exit 0
    sleep 2
done
echo "== user-collections did not answer /health within 60s — the aliveness check below will say so"

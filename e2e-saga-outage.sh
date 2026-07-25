#!/bin/bash
# The account-deletion saga under a PARTICIPANT OUTAGE — e2e/features/participant-outage.feature
# (@outage), deliberately OUTSIDE the default ./e2e-saga.sh run: this scenario stops and starts
# a real compose container (user-collections) and then watches the saga capitulate on the
# arithmetic of its own retry policy — 120s purge timeout + 3 re-commands on 15s sweeps —
# so one run takes several minutes by design.
#
# The stack must already be up (./infra-up.sh) — this script checks and says so instead of
# starting a many-minute build behind your back. It also starts user-collections first, in case
# an interrupted previous run left it stopped.
set -euo pipefail
cd "$(dirname "$0")"

SECURITY_URL=${SECURITY_URL:-http://localhost:8080}
MEMES_URL=${MEMES_URL:-http://localhost:8083}
COMMENTS_URL=${COMMENTS_URL:-http://localhost:8085}
COLLECTIONS_URL=${COLLECTIONS_URL:-http://localhost:8092}
OFFBOARDING_URL=${OFFBOARDING_URL:-http://localhost:8094}
MAILPIT_URL=${MAILPIT_URL:-http://localhost:8025}

# the harness needs Node >= 20.3 (AbortSignal.any in e2e/support/world.mjs) — say so up front
# instead of failing mid-scenario with a bare TypeError
node -e 'const [maj,min]=process.versions.node.split(".").map(Number); if (maj<20||(maj===20&&min<3)) { console.error(`FAIL: the e2e harness needs Node >= 20.3 (AbortSignal.any), found ${process.versions.node}`); process.exit(1); }'

# a previous @outage run that died mid-scenario leaves the favourites service stopped — heal
# that BEFORE the aliveness check, so the check reports real trouble, not old trouble (the
# same self-healing preflight lives in ./e2e-saga.sh)
if ! docker compose ps --services --status running 2>/dev/null | grep -qx user-collections; then
    echo "== user-collections is not running (leftover of an interrupted @outage run?) — starting it"
    docker compose start user-collections >/dev/null 2>&1 || true
    for i in $(seq 1 30); do
        curl -sf --max-time 3 "$COLLECTIONS_URL/health" >/dev/null 2>&1 && break
        sleep 2
    done
fi

echo "== checking every member of the chain is alive"
down=()
for probe in "security $SECURITY_URL/health" \
             "memes $MEMES_URL/memes/hot" \
             "user-collections $COLLECTIONS_URL/health" \
             "offboarding $OFFBOARDING_URL/health" \
             "mailpit $MAILPIT_URL/api/v1/info"; do
    name=${probe%% *}; url=${probe#* }
    ok=""
    for i in 1 2 3 4 5; do curl -sf --max-time 3 "$url" >/dev/null 2>&1 && { ok=1; break; }; sleep 2; done
    [ -n "$ok" ] || down+=("$name ($url)")
done
if [ "${#down[@]}" -gt 0 ]; then
    echo "FAIL: the portal stack is not (fully) running — unreachable: ${down[*]}"
    echo "      start it first: ./infra-up.sh   (then wait for every service to report healthy)"
    exit 1
fi

# the scenario samples the sign-in door of an account in deletion limbo, and each such sample
# counts as a failed attempt — start from a clean brute-force slate so leftovers from manual
# clicking cannot push this run over the block threshold (same global-wipe caveat as
# ./e2e-saga.sh: acceptable on a dev stack and nowhere else)
docker compose -p security exec -T postgres psql -q -U postgres -d security \
    -c "DELETE FROM authentication_blocks; DELETE FROM rejected_authentications;" >/dev/null 2>&1 || true

cd e2e
if [ ! -d node_modules ]; then
    echo "== installing the harness (first run only)"
    npm install --no-audit --no-fund
fi

echo "== staging the outage and watching the saga give the account back (several minutes)"
# E2E_OUTAGE_PROFILE=1 is the marker the infrastructure-manipulating steps insist on
# (steps/participant-outage.steps.mjs): without it they refuse to stop any container
SECURITY_URL="$SECURITY_URL" MEMES_URL="$MEMES_URL" COMMENTS_URL="$COMMENTS_URL" \
    COLLECTIONS_URL="$COLLECTIONS_URL" MAILPIT_URL="$MAILPIT_URL" \
    E2E_OUTAGE_PROFILE=1 \
    npx cucumber-js --config cucumber.mjs --profile outage

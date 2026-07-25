#!/bin/bash
# The account-deletion saga end to end, IN THE USER'S LANGUAGE — e2e/features/account-deletion.feature
# run with cucumber-js over plain HTTP against the LIVE portal stack (no browser, no stubs: real
# security, real broker, real mailbox, real content services). The choreography underneath is
# already specified in microservice-offboarding; here only the promise made to the person counts.
#
# The stack must already be up (./infra-up.sh) — this script checks and says so instead of
# starting a many-minute build behind your back. Repeatable: every run registers its own
# run-unique accounts, so green stays green on a warm stack.
set -euo pipefail
cd "$(dirname "$0")"

SECURITY_URL=${SECURITY_URL:-http://localhost:8080}
MEMES_URL=${MEMES_URL:-http://localhost:8083}
COMMENTS_URL=${COMMENTS_URL:-http://localhost:8085}
COLLECTIONS_URL=${COLLECTIONS_URL:-http://localhost:8092}
OFFBOARDING_URL=${OFFBOARDING_URL:-http://localhost:8094}
MAILPIT_URL=${MAILPIT_URL:-http://localhost:8025}

echo "== checking every member of the chain is alive"
down=()
for probe in "security $SECURITY_URL/health" \
             "memes $MEMES_URL/memes/hot" \
             "comments $COMMENTS_URL/memes/warmup/comments" \
             "user-collections $COLLECTIONS_URL/health" \
             "offboarding $OFFBOARDING_URL/health" \
             "mailpit $MAILPIT_URL/api/v1/info"; do
    name=${probe%% *}; url=${probe#* }
    ok=""
    for i in 1 2 3; do curl -sf --max-time 3 "$url" >/dev/null 2>&1 && { ok=1; break; }; sleep 1; done
    [ -n "$ok" ] || down+=("$name ($url)")
done
if [ "${#down[@]}" -gt 0 ]; then
    echo "FAIL: the portal stack is not (fully) running — unreachable: ${down[*]}"
    echo "      start it first: ./infra-up.sh   (then wait for every service to report healthy)"
    exit 1
fi

# manual clicking on a shared dev stack may have tripped the brute-force source block, which
# would turn this run's sign-ins into refusals about the PAST, not about the scenario — same
# hygiene as ../shared/infra-smoke.sh, and just as optional. NOTE: this wipes the tables
# GLOBALLY — every block and rejection on the stack, not just this run's — a deliberate
# side-effect that is acceptable on a dev stack and nowhere else
docker compose -p security exec -T postgres psql -q -U postgres -d security \
    -c "DELETE FROM authentication_blocks; DELETE FROM rejected_authentications;" >/dev/null 2>&1 || true

cd e2e
if [ ! -d node_modules ]; then
    echo "== installing the harness (first run only)"
    npm install --no-audit --no-fund
fi

echo "== telling the user's story against the live stack"
SECURITY_URL="$SECURITY_URL" MEMES_URL="$MEMES_URL" COMMENTS_URL="$COMMENTS_URL" \
    COLLECTIONS_URL="$COLLECTIONS_URL" MAILPIT_URL="$MAILPIT_URL" \
    npx cucumber-js --config cucumber.mjs

#!/bin/bash
# The gallery's browser entry point, end to end — and END TO END means it: the scenarios run
# against the REAL portal stack (docker compose), where security keeps its state in Postgres,
# mails travel the outbox -> Kafka -> microservice-email -> SMTP road into Mailpit, the
# account-deletion saga is carried by offboarding, and memes/comments/collections purge their
# content and confirm it for real.
#
# It used to boot four jars with in-memory stores and no broker. That made the identity half
# quick to test and the saga half impossible — security's `test` environment has no outbox
# publisher at all (@Requires(notEnv = "test")), so nothing ever reached a broker and a deleted
# account merely locked. An end-to-end missing a member proves nothing about the member it
# skipped (the owner, 2026-07-20: „chujowo robić e2e bez jakiegoś członu").
#
# The stack is started here when it is not already up; KEEP_STACK=1 leaves it running afterwards
# (the fast loop while writing scenarios), otherwise this takes it back down.
set -euo pipefail
cd "$(dirname "$0")"

SECURITY_URL=${SECURITY_URL:-http://localhost:8080}
MEMES_URL=${MEMES_URL:-http://localhost:8083}
COMMENTS_URL=${COMMENTS_URL:-http://localhost:8085}
COLLECTIONS_URL=${COLLECTIONS_URL:-http://localhost:8092}
OFFBOARDING_URL=${OFFBOARDING_URL:-http://localhost:8094}
MAILPIT_URL=${MAILPIT_URL:-http://localhost:8025}
UI_URL=${UI_URL:-$MEMES_URL}          # the gallery the memes jar itself serves — the shipped build

started_here=0
if ! curl -sf "$MEMES_URL/memes/hot" >/dev/null 2>&1; then
    echo "== the portal stack is not up — starting it (jars build on the host first)"
    (cd ../.. && ./infra-up.sh)
    started_here=1
fi

cleanup() {
    if [ "$started_here" = 1 ] && [ "${KEEP_STACK:-0}" != 1 ]; then
        echo "== taking the stack back down (KEEP_STACK=1 leaves it running)"
        (cd ../.. && ./infra-down.sh) || true
    fi
}
trap cleanup EXIT

echo "== waiting for every member of the chain"
for url in "$SECURITY_URL/health" "$MEMES_URL/memes/hot" \
           "$COMMENTS_URL/memes/warmup/comments" "$COLLECTIONS_URL/health" \
           "$OFFBOARDING_URL/health" "$MAILPIT_URL/api/v1/info" "$UI_URL"; do
    for i in $(seq 1 90); do
        curl -sf "$url" >/dev/null && break
        [ "$i" = 90 ] && { echo "FAIL: $url did not come up"; exit 1; }
        sleep 2
    done
done

# A clean slate for the brute-force ledger before the suite — GLOBALLY, every block and rejection
# on the stack, the same dev-stack-only side effect the saga scripts take.
#
# Scenarios here mistype passwords ON PURPOSE, and blocks are placed on the SOURCE, which every
# scenario shares on a CI runner. Since failures are counted per (source, account), one scenario's
# deliberate mistakes no longer bleed into the next — but a block already placed by an earlier run
# against this same stack would still be waiting, so the suite starts from zero rather than from
# whatever the previous run left behind.
docker compose -p security exec -T postgres psql -q -U postgres -d security \
    -c "DELETE FROM authentication_blocks; DELETE FROM rejected_authentications;" >/dev/null 2>&1 || true

echo "== driving the gallery through the browser"
SECURITY_URL="$SECURITY_URL" MEMES_URL="$MEMES_URL" COMMENTS_URL="$COMMENTS_URL" \
    MAILPIT_URL="$MAILPIT_URL" UI_URL="$UI_URL" \
    npx cucumber-js --config e2e/cucumber.mjs

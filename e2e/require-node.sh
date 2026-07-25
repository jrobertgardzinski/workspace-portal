#!/bin/bash
# The e2e harness's Node floor, stated ONCE — ../e2e-saga.sh and ../e2e-saga-outage.sh both
# run this before they do anything expensive. It used to be the same one-liner copy-pasted
# into both runners, which is exactly the kind of duplication that drifts the day the floor
# moves. Run it standalone too, if you just want to know: ./e2e/require-node.sh
#
# Why 20.3 and not "some recent Node": e2e/support/world.mjs caps every request with
# AbortSignal.timeout() and composes that cap with the caller's own signal via
# AbortSignal.any() (world.mjs:29). AbortSignal.any landed in Node 20.3.0; on anything older
# the harness dies mid-scenario with a bare "AbortSignal.any is not a function" TypeError,
# which tells the reader nothing about the real problem. Say it here instead, up front.
set -euo pipefail

node -e 'const [maj,min]=process.versions.node.split(".").map(Number); if (maj<20||(maj===20&&min<3)) { console.error(`FAIL: the e2e harness needs Node >= 20.3 (AbortSignal.any), found ${process.versions.node}`); process.exit(1); }'

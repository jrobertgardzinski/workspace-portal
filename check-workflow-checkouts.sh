#!/usr/bin/env bash
# Every module the shared aggregator builds must be checked out by every workflow that builds it.
#
# CI clones the sub-repositories by hand, one `actions/checkout` per module, because each one is an
# independent git repository. That list is maintained by hand and the aggregator's module list is
# not, so the two drift apart silently: on 2026-09-12 `shared/pom.xml` gained observation, envelope
# and account-closure, the workflows were not updated, and every nightly saga e2e from that day on
# died with "child module .../shared/observation of shared/pom.xml does not exist" — twelve red runs
# nobody was told about, because the failure looks like an infrastructure hiccup and the local
# `estate.sh` clone (driven by estate/shared.repos, which WAS updated) keeps working.
#
# This script makes that drift loud. Run it against a checkout of workspace-shared:
#   ./check-workflow-checkouts.sh ../shared          # locally, beside the portal workspace
#   ./check-workflow-checkouts.sh shared             # in CI, after the checkout steps
# It exits non-zero and names the missing module the moment a workflow falls behind the aggregator.
set -euo pipefail

SHARED="${1:-../shared}"
POM="$SHARED/pom.xml"
WORKFLOWS=(.github/workflows/ci.yml .github/workflows/e2e-saga.yml)

if [[ ! -f "$POM" ]]; then
  echo "[check-workflow-checkouts] no aggregator at $POM"
  echo "  pass the path to a workspace-shared checkout: $0 <path-to-shared>"
  exit 2
fi

# The aggregator's own module list is the reference; a directory name here is a checkout path there.
mapfile -t MODULES < <(sed -n 's:.*<module>\(.*\)</module>.*:\1:p' "$POM")
if [[ ${#MODULES[@]} -eq 0 ]]; then
  echo "[check-workflow-checkouts] $POM declares no modules — is this the right file?"
  exit 2
fi

status=0
for wf in "${WORKFLOWS[@]}"; do
  [[ -f "$wf" ]] || { echo "[check-workflow-checkouts] missing workflow $wf"; status=1; continue; }
  missing=()
  for m in "${MODULES[@]}"; do
    # The Python stubs and the portal services are not shared modules, so only shared/<module> counts.
    grep -qE "^[[:space:]]*path:[[:space:]]*shared/${m}[[:space:]]*(#.*)?$" "$wf" || missing+=("$m")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    status=1
    echo "[check-workflow-checkouts] $wf does not check out: ${missing[*]}"
    echo "    $POM builds ${#MODULES[@]} modules; this workflow is short of ${#missing[@]}."
    echo "    Maven will refuse with 'child module ... does not exist' before a single container starts."
  else
    echo "[check-workflow-checkouts] $wf covers all ${#MODULES[@]} shared modules"
  fi
done

exit $status

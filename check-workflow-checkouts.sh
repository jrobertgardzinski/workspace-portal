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
# The same list drifts a second way, and did on 2026-09-26: memes' and comments' `ci.yml` installed
# `account-closure` BEFORE `user-id`, which is fine until the closure vocabulary starts speaking
# UserId — from that commit on every run died in "Install the shared libraries" with "Could not find
# artifact user-id", and nobody was told, because the checkout list was complete and the local builds
# were green. user-collections happened to list the two the other way round and stayed green, which
# is what made the failure look like somebody else's problem. So the order is checked too, against
# the dependencies the libraries' own poms declare.
#
# This script makes that drift loud. Run it against a checkout of workspace-shared:
#   ./check-workflow-checkouts.sh ../shared          # locally, beside the portal workspace
#   ./check-workflow-checkouts.sh shared             # in CI, after the checkout steps
# It exits non-zero and names the missing module the moment a workflow falls behind the aggregator.
set -euo pipefail

SHARED="${1:-../shared}"
POM="$SHARED/pom.xml"
WORKFLOWS=(.github/workflows/ci.yml .github/workflows/e2e-saga.yml)
# Every workflow that installs shared libraries by hand, this workspace's own and each service's.
mapfile -t INSTALLERS < <(printf '%s\n' "${WORKFLOWS[@]}" microservice-*/.github/workflows/*.yml \
  | while read -r f; do [[ -f "$f" ]] && grep -qE '\-f[[:space:]]+[A-Za-z0-9_.-]+/pom\.xml[[:space:]]+install' "$f" && echo "$f"; done)

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

# A library must be installed before anything that depends on it: `mvn install` resolves from the
# local repository, so a consumer built first cannot see a sibling that is not there yet.
# A guard that covers nothing must say so: in CI these files only exist after the services are
# checked out, and a silent pass there is exactly how the order drifted unnoticed in the first place.
if [[ ${#INSTALLERS[@]} -eq 0 ]]; then
  echo "[check-workflow-checkouts] no workflow installs shared libraries by hand here —"
  echo "    run this beside the service checkouts (portal/microservice-*), or the order is unchecked."
  status=1
fi

for wf in "${INSTALLERS[@]}"; do
  mapfile -t installed < <(sed -n 's:.*-f[[:space:]]\+\([A-Za-z0-9_.-]\+\)/pom\.xml[[:space:]]\+install.*:\1:p' "$wf")
  [[ ${#installed[@]} -gt 0 ]] || continue
  inverted=()
  for i in "${!installed[@]}"; do
    name="${installed[$i]}"
    pom="$SHARED/$name/pom.xml"
    [[ -f "$pom" ]] || continue
    for j in "${!installed[@]}"; do
      dep="${installed[$j]}"
      [[ "$dep" == "$name" ]] && continue
      (( j < i )) && continue          # already installed earlier: correct order
      grep -qE "^[[:space:]]*<artifactId>${dep}</artifactId>[[:space:]]*$" "$pom" \
        && inverted+=("$name before $dep, which $name's pom depends on")
    done
  done
  if [[ ${#inverted[@]} -gt 0 ]]; then
    status=1
    echo "[check-workflow-checkouts] $wf installs the shared libraries out of dependency order:"
    printf '    %s\n' "${inverted[@]}"
    echo "    Maven will refuse with 'Could not find artifact' before a single test runs."
  else
    echo "[check-workflow-checkouts] $wf installs ${#installed[@]} libraries in dependency order"
  fi
done

exit $status

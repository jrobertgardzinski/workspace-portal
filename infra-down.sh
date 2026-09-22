#!/bin/bash
# Stop the stack.
#
# The compose project is shared with ../formula (both are named "security"), so this stops the
# identity services the formula world may be using too — the products share one dev stack.
#
# About -v, which is why this script is no longer three lines. `docker compose down -v` drops
# every volume the MERGED file declares, and the merged file includes ../shared: that is
# security_pgdata (the identity database BOTH products sign in against) and the observability
# stores, next to the portal's own five. So the old `-v` deleted every account in the estate
# while formula's own databases survived holding rows keyed to the addresses it had just erased.
# The header said it "stops identity services"; nothing said it destroyed their data.
#
#   -v   the PORTAL's databases and object store — memes, comments, collections, offboarding,
#        MinIO. Identity, Loki and Tempo are left alone. This is what "fresh databases next
#        time" always meant for someone working on the portal.
#   -V   everything the merged file declares, identity included. Named separately and spelled
#        loudly because it takes the other product's accounts with it.
#
# The portal's list is not hardcoded here: it is read out of THIS file's own top-level
# `volumes:` block, so a volume added to the compose file is dropped by -v without anyone
# remembering to update a list. A copy of a list is the defect; keeping a better copy is not
# the cure.
set -euo pipefail
cd "$(dirname "$0")"

project=$(docker compose config --format json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])' 2>/dev/null || echo security)

own_volumes() {
    # the top-level volumes: block of this file only — the included ../shared files declare
    # theirs in their own, and those are the ones -v must not touch
    awk '/^volumes:/{inside=1; next} inside && /^[^ ]/{inside=0} inside && /^  [a-z0-9_-]+:/{gsub(/[ :]/,""); print}' docker-compose.yml
}

case "${1-}" in
    -v|--volumes)
        shift
        docker compose down --remove-orphans "$@"
        mapfile -t volumes < <(own_volumes)
        echo "dropping the portal's own volumes (identity is NOT touched): ${volumes[*]}"
        for volume in "${volumes[@]}"; do
            docker volume rm -f "${project}_${volume}" >/dev/null 2>&1 || true
        done
        ;;
    -V|--all-volumes)
        shift
        echo "WARNING: dropping EVERY volume the merged compose file declares — this includes the"
        echo "         identity database shared with ../formula. Every account in the estate goes."
        docker compose down --remove-orphans -v "$@"
        ;;
    *)
        docker compose down --remove-orphans "$@"
        ;;
esac

#!/bin/bash
# Stop the stack. Add -v to also drop the Postgres volumes (fresh databases next time).
# The compose project is shared with ../formula (both are named "security"), so this also
# stops identity services the formula world may be using — the products share one dev stack.
set -euo pipefail
cd "$(dirname "$0")"
docker compose down --remove-orphans "$@"

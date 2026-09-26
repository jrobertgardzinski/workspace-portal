#!/usr/bin/env bash
# Fills author_id in memes and comments from security's users table, for rows written before the
# id column existed (V13 in memes, V7 in comments). Idempotent: only rows without an id are touched.
# Rows still without an id afterwards belong to accounts security no longer holds; they render as
# masked addresses until the address column goes.
#
# Runs against the compose stack (project "security", the one every up-script uses).
set -euo pipefail
P=${COMPOSE_PROJECT:-security}

pg() { docker compose -p "$P" exec -T "$1" psql -v ON_ERROR_STOP=1 -U postgres -d "$2" "${@:3}"; }

values=$(pg postgres security -Atc \
    "SELECT '(''' || id || ''',''' || replace(email, '''', '''''') || ''')' FROM users" | paste -sd, -)
if [ -z "$values" ]; then
    echo "security holds no users; nothing to backfill"
    exit 0
fi

for triple in memes-postgres:memes:memes comments-postgres:comments:comments; do
    IFS=: read -r service db table <<<"$triple"
    pg "$service" "$db" <<SQL
UPDATE $table t SET author_id = v.id::uuid
  FROM (VALUES $values) AS v(id, email)
 WHERE lower(t.author) = lower(v.email) AND t.author_id IS NULL;
SELECT '$table' AS "table", count(*) AS rows,
       count(*) FILTER (WHERE author_id IS NULL) AS still_without_id FROM $table;
SQL
done

#!/usr/bin/env bash
# Fills the author's id in memes and comments from security's users table, for rows written before
# the id column existed. Idempotent: only rows without an id are touched. A row still without an id
# afterwards is either anonymised (author = 'deleted account') or belongs to an account security no
# longer holds; both render as "deleted account". Collections need no backfill: user_id is NOT NULL
# and the address column is gone.
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

# service:database:table:address column:id column
for row in memes-postgres:memes:memes:author:author_id \
           comments-postgres:comments:comments:author:author_id; do
    IFS=: read -r service db table address id <<<"$row"
    pg "$service" "$db" <<SQL
UPDATE $table t SET $id = v.id::uuid
  FROM (VALUES $values) AS v(id, email)
 WHERE lower(t.$address) = lower(v.email) AND t.$id IS NULL;
SELECT '$table' AS "table", count(*) AS rows,
       count(*) FILTER (WHERE $id IS NULL) AS still_without_id FROM $table;
SQL
done

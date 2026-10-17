#!/bin/bash
# Production only (mounted by deploy/compose.prod.yml). Runs once, right after 01-init.sql, when Postgres
# creates its data directory: replaces each service role's development password with the one from .env.
set -euo pipefail

for svc in auth profile job ingestion referral analyzer tracker notification billing interview analytics; do
    var="${svc^^}_DB_PASSWORD"
    pw="${!var:-}"
    if [ -z "$pw" ]; then
        echo "02-set-passwords: $var is not set" >&2
        exit 1
    fi
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
         -v role="${svc}_svc" -v pw="$pw" <<'SQL'
alter role :"role" with password :'pw';
SQL
done
echo "02-set-passwords: service role passwords set"

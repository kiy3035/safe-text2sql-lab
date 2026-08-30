#!/usr/bin/env bash
set -Eeuo pipefail

: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=app_db_password="$APP_DB_PASSWORD" <<-'EOSQL'
SELECT format(
  'CREATE ROLE text2sql_ro LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION',
  :'app_db_password'
) \gexec
EOSQL

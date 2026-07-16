#!/bin/sh
set -eu

: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_ADMIN_USER:?POSTGRES_ADMIN_USER is required}"
: "${POSTGRES_ADMIN_PASSWORD:?POSTGRES_ADMIN_PASSWORD is required}"
: "${SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME:?SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME is required}"
: "${SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD:?SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD is required}"

case "$SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME" in
  *[!A-Za-z0-9_-]*|'')
    echo "SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME must contain only letters, digits, underscore or dash" >&2
    exit 2
    ;;
esac

export PGPASSWORD="$POSTGRES_ADMIN_PASSWORD"

ready_attempt=1
ready_max_attempts=60
while ! pg_isready -h "$POSTGRES_HOST" -U "$POSTGRES_ADMIN_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; do
  if [ "$ready_attempt" -ge "$ready_max_attempts" ]; then
    echo "PostgreSQL did not become ready after ${ready_max_attempts} attempts" >&2
    exit 1
  fi
  sleep 2
  ready_attempt=$((ready_attempt + 1))
done

psql \
  --host "$POSTGRES_HOST" \
  --username "$POSTGRES_ADMIN_USER" \
  --dbname "$POSTGRES_DB" \
  --set ON_ERROR_STOP=1 \
  --set worker_user="$SOUNDCONNECT_MEDIA_WORKER_POSTGRES_USERNAME" \
  --set worker_password="$SOUNDCONNECT_MEDIA_WORKER_POSTGRES_PASSWORD" <<'SQL'
SELECT format(
  'CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'worker_user', :'worker_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'worker_user')
\gexec

SELECT format(
  'ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION',
  :'worker_user', :'worker_password'
)
\gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'worker_user')
\gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'worker_user')
\gexec
SELECT format('REVOKE CREATE ON SCHEMA public FROM %I', :'worker_user')
\gexec
SELECT format('REVOKE ALL PRIVILEGES ON TABLE public.tbl_media_asset FROM %I', :'worker_user')
\gexec
SELECT format('GRANT SELECT ON TABLE public.tbl_media_asset TO %I', :'worker_user')
\gexec
SELECT format(
  'GRANT UPDATE (' ||
  'status, updated_at, playback_url, thumbnail_url, duration_seconds, width, height, streaming_protocol, ' ||
  'transcode_attempt_token, transcode_lease_until, transcode_attempt_deadline, ' ||
  'transcode_cleanup_not_before, transcode_attempt_count, transcode_retry_pending, ' ||
  'transcode_retain_source_after_cleanup' ||
  ') ON TABLE public.tbl_media_asset TO %I',
  :'worker_user'
)
\gexec
SQL

unset PGPASSWORD
echo "Configured least-privilege PostgreSQL media-worker role."

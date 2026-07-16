#!/bin/sh
set -eu

: "${RABBITMQ_MANAGEMENT_URL:?RABBITMQ_MANAGEMENT_URL is required}"
: "${RABBITMQ_ADMIN_USER:?RABBITMQ_ADMIN_USER is required}"
: "${RABBITMQ_ADMIN_PASSWORD:?RABBITMQ_ADMIN_PASSWORD is required}"
: "${SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME:?SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME is required}"
: "${SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD:?SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD is required}"

case "$SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME" in
  *[!A-Za-z0-9._-]*|'')
    echo "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME contains unsupported characters" >&2
    exit 2
    ;;
esac
case "$SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD" in
  *[!A-Za-z0-9._~-]*|'')
    echo "SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD must be URL-safe (letters, digits, . _ ~ -)" >&2
    exit 2
    ;;
esac

auth="${RABBITMQ_ADMIN_USER}:${RABBITMQ_ADMIN_PASSWORD}"
ready_attempt=1
ready_max_attempts=60
while ! curl --fail --silent --user "$auth" \
  "${RABBITMQ_MANAGEMENT_URL}/api/overview" >/dev/null 2>&1; do
  if [ "$ready_attempt" -ge "$ready_max_attempts" ]; then
    echo "RabbitMQ management API did not become ready after ${ready_max_attempts} attempts" >&2
    exit 1
  fi
  sleep 2
  ready_attempt=$((ready_attempt + 1))
done

curl --fail --silent --show-error --user "$auth" \
  --header 'content-type: application/json' \
  --request PUT \
  --data-binary "{\"password\":\"${SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_PASSWORD}\",\"tags\":\"\"}" \
  "${RABBITMQ_MANAGEMENT_URL}/api/users/${SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME}" >/dev/null

# Consume only the durable HLS queue. There is no exchange write permission and
# no access to mail/notification queues. Exact-queue configure is required for
# the listener container's passive queue declaration.
curl --fail --silent --show-error --user "$auth" \
  --header 'content-type: application/json' \
  --request PUT \
  --data-binary '{"configure":"^media\\.transcode\\.video\\.hls$","write":"^$","read":"^media\\.transcode\\.video\\.hls$"}' \
  "${RABBITMQ_MANAGEMENT_URL}/api/permissions/%2F/${SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_USERNAME}" >/dev/null

echo "Configured least-privilege RabbitMQ media-worker consumer."

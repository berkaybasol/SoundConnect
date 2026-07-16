#!/bin/sh
set -eu

marker="${SOUNDCONNECT_MEDIA_WORKER_HEALTH_FILE:-/tmp/soundconnect-media-worker.ready}"
max_age="${SOUNDCONNECT_MEDIA_WORKER_HEALTH_MAX_AGE_SECONDS:-60}"

case "$max_age" in
  ''|*[!0-9]*) exit 1 ;;
esac
[ "$max_age" -gt 0 ] || exit 1
[ -r "$marker" ] || exit 1

timestamp="$(cat "$marker")"
case "$timestamp" in
  ''|*[!0-9]*) exit 1 ;;
esac

now="$(date +%s)"
age=$((now - timestamp))

# A future timestamp is also unhealthy; it can hide a stopped scheduler after
# a clock correction or a malformed/manual marker.
[ "$age" -ge 0 ] && [ "$age" -le "$max_age" ]

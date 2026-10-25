#!/bin/sh
set -eu

set -- -XX:MaxRAMPercentage=70 -Dsun.net.httpserver.maxReqHeaders=64
for setting in request response; do
  case "$setting" in
    request) value=${OBJECTSTORE_REQUEST_TIMEOUT_SECONDS:-0}; property=maxReqTime ;;
    response) value=${OBJECTSTORE_RESPONSE_TIMEOUT_SECONDS:-0}; property=maxRspTime ;;
  esac
  case "$value" in
    ''|*[!0-9]*) echo "Invalid $setting timeout" >&2; exit 1 ;;
  esac
  while [ "${value#0}" != "$value" ]; do value=${value#0}; done
  value=${value:-0}
  [ "${#value}" -le 5 ] || { echo "$setting timeout exceeds one day" >&2; exit 1; }
  if [ "$value" -gt 0 ]; then
    [ "$value" -le 86400 ] || { echo "$setting timeout exceeds one day" >&2; exit 1; }
    set -- "$@" "-Dsun.net.httpserver.$property=$value"
  fi
done

exec java "$@" --add-modules jdk.httpserver,java.net.http \
  -cp /app:/app/postgresql.jar:/app/hash4j.jar cloud.lunarsky.store.Main

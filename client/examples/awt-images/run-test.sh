#!/usr/bin/env bash
set -euo pipefail

client_dir="$(cd "$(dirname "$0")/../.." && pwd)"
project_dir="$(cd "$client_dir/.." && pwd)"
container="objectstore-image-example"
volume="objectstore-image-example-data"
image="${OBJECTSTORE_TEST_IMAGE:-objectstore-image-example:local}"

if ! docker container inspect "$container" >/dev/null 2>&1; then
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    docker build --tag "$image" "$project_dir"
  fi
  export S3_ACCESS_KEY="ImageExampleTest1"
  export S3_SECRET_KEY="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
  docker volume create "$volume" >/dev/null
  docker run --detach --name "$container" \
    --publish 127.0.0.1:9002:9000 \
    --volume "$volume:/data" \
    --env S3_ACCESS_KEY --env S3_SECRET_KEY \
    --env S3_BUCKET=photos --env S3_REGION=us-east-1 \
    --env MAX_OBJECT_BYTES=67108864 --env MAX_TOTAL_BYTES=1073741824 \
    "$image" >/dev/null
else
  while IFS='=' read -r key value; do
    case "$key" in
      S3_ACCESS_KEY) export S3_ACCESS_KEY="$value" ;;
      S3_SECRET_KEY) export S3_SECRET_KEY="$value" ;;
    esac
  done < <(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container")
  if [[ "$(docker inspect --format '{{.State.Running}}' "$container")" != true ]]; then
    docker start "$container" >/dev/null
  fi
fi

for attempt in {1..40}; do
  if curl --silent --fail --output /dev/null http://127.0.0.1:9002/health; then
    break
  fi
  sleep 0.25
done
if ! curl --silent --fail --output /dev/null http://127.0.0.1:9002/health; then
  echo "The isolated ObjectStore container did not become healthy; check docker logs $container." >&2
  exit 1
fi
export S3_ENDPOINT=http://127.0.0.1:9002
export S3_REGION=us-east-1
export S3_BUCKET=photos
export S3_ALLOW_HTTP=true
export S3_AUTO_CONNECT=true
exec "$client_dir/examples/awt-images/run.sh"

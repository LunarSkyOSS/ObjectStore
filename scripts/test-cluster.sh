#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
env_file=${1:?Usage: sh scripts/test-cluster.sh /path/to/local-cluster.env}
host_port=${CLUSTER_HOST_PORT:-9001}
compose() { docker compose --env-file "$env_file" -f compose.cluster.yaml "$@"; }
restore() { compose start metadata node-a node-b >/dev/null 2>&1 || true; }
trap restore EXIT
compose up -d --build
run_phase() {
  compose exec -T gateway java --add-modules jdk.httpserver,java.net.http \
    -cp /app:/app/postgresql.jar cloud.lunarsky.store.ClusterIntegrationTest "$1"
}
wait_ready() {
  attempt=0
  until curl -fsS -o /dev/null "http://127.0.0.1:$host_port/ready" 2>/dev/null; do
    attempt=$((attempt + 1))
    [ "$attempt" -lt 30 ] || return 1
    sleep 1
  done
}
run_phase basic
run_phase same-host
run_phase concurrent
compose stop node-a
run_phase degraded
compose stop node-b
run_phase quorum-lost
compose start node-a node-b
wait_ready
run_phase recovered
segment_id=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  "SELECT s.segment_id FROM cluster_segments s JOIN cluster_objects o ON o.generation=s.generation WHERE o.object_key='cluster-test/survivor' LIMIT 1")
printf '%s\n' "$segment_id" | grep -Eq '^[0-9a-f-]{36}$'
shard=$(printf '%s' "$segment_id" | cut -c1-2)
compose exec -T node-a sh -c 'printf corrupted > "/data/segments/$1/$2"' _ "$shard" "$segment_id"
run_phase recovered
compose run --rm -T repair
expected=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  "SELECT encode(s.sha256,'hex') FROM cluster_segments s JOIN cluster_objects o ON o.generation=s.generation WHERE o.object_key='cluster-test/survivor' LIMIT 1")
actual=$(compose exec -T node-a sha256sum "/data/segments/$shard/$segment_id" | cut -d' ' -f1)
[ "$expected" = "$actual" ]
compose stop metadata
status=$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:$host_port/ready")
[ "$status" = 503 ]
compose start metadata
wait_ready
run_phase recovered
python3 scripts/test-cluster-http.py "$env_file"
compose --profile expansion up -d node-d
compose run --rm -T --no-deps --entrypoint /usr/local/bin/objectstore gateway \
  cluster-join http://node-d:9100 5f1447b5-3f9e-457b-8ee7-e26f0c475b5f
export CLUSTER_NODES=http://node-a:9100,http://node-b:9100,http://node-c:9100,http://node-d:9100
compose up -d --no-deps gateway
wait_ready
run_phase recovered
run_phase joined
echo 'Cluster failure tests passed'

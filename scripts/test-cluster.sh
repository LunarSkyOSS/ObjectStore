#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
env_file=${1:?Usage: sh scripts/test-cluster.sh /path/to/local-cluster.env}
host_port=${CLUSTER_HOST_PORT:-9001}
backup_dir=
compose() { docker compose --env-file "$env_file" -f compose.cluster.yaml "$@"; }
restore() {
  compose stop maintenance >/dev/null 2>&1 || true
  compose start metadata node-a node-b node-c >/dev/null 2>&1 || true
  compose exec -T metadata psql -U objectstore -d postgres -c \
    'ALTER DATABASE objectstore RESET default_transaction_read_only' >/dev/null 2>&1 || true
  if [ -n "$backup_dir" ]; then rm -rf "$backup_dir"; fi
}
trap restore EXIT
compose up -d --build
run_phase() {
  compose exec -T gateway java --add-modules jdk.httpserver,java.net.http \
    -cp /app:/app/postgresql.jar:/app/hash4j.jar cloud.lunarsky.store.ClusterIntegrationTest "$1"
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
run_phase multipart-stage
compose restart gateway
wait_ready
part_segment=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  "SELECT segment_id FROM cluster_upload_segments LIMIT 1")
printf '%s\n' "$part_segment" | grep -Eq '^[0-9a-f-]{36}$'
part_shard=$(printf '%s' "$part_segment" | cut -c1-2)
compose exec -T node-a sh -c 'printf corrupted > "/data/segments/$1/$2"' _ "$part_shard" "$part_segment"
compose run --rm -T repair
part_expected=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  "SELECT encode(sha256,'hex') FROM cluster_upload_segments WHERE segment_id='$part_segment'")
part_actual=$(compose exec -T node-a sha256sum "/data/segments/$part_shard/$part_segment" | cut -d' ' -f1)
[ "$part_expected" = "$part_actual" ]
run_phase same-host
run_phase concurrent
compose stop node-a
run_phase degraded
run_phase multipart-complete
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
compose exec -T metadata psql -U objectstore -d postgres -c \
  'ALTER DATABASE objectstore SET default_transaction_read_only=on' >/dev/null
status=$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:$host_port/ready")
[ "$status" = 503 ]
compose exec -T metadata psql -U objectstore -d postgres -c \
  'ALTER DATABASE objectstore RESET default_transaction_read_only' >/dev/null
wait_ready
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
compose run --rm -T repair
run_phase balanced
export CLUSTER_MAINTENANCE_INTERVAL_SECONDS=1
compose --profile automatic up -d maintenance
node_a_id=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  "SELECT node_id FROM cluster_nodes WHERE endpoint='http://node-a:9100'")
segment_id=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  "SELECT s.segment_id FROM cluster_segments s JOIN cluster_objects o ON o.generation=s.generation WHERE '$node_a_id'::uuid = ANY(s.replica_ids) LIMIT 1")
expected=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  "SELECT encode(s.sha256,'hex') FROM cluster_segments s WHERE s.segment_id='$segment_id' LIMIT 1")
shard=$(printf '%s' "$segment_id" | cut -c1-2)
compose exec -T node-a sh -c 'printf corrupted > "/data/segments/$1/$2"' _ "$shard" "$segment_id"
attempt=0
while :; do
  actual=$(compose exec -T node-a sha256sum "/data/segments/$shard/$segment_id" | cut -d' ' -f1)
  [ "$actual" = "$expected" ] && break
  attempt=$((attempt + 1))
  [ "$attempt" -lt 90 ] || { echo 'Automatic repair did not restore the replica' >&2; exit 1; }
  sleep 1
done
compose stop maintenance
export CLUSTER_GC_TEST_MODE=true CLUSTER_GC_MIN_AGE_SECONDS=0
compose stop node-c
if gc_refusal=$(compose run --rm -T gc --apply 2>&1); then
  echo 'Cleanup proceeded while replicas needed repair' >&2
  exit 1
fi
printf '%s\n' "$gc_refusal" | grep -q 'Refusing cleanup while live segments need repair'
compose start node-c
before=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  'SELECT count(*) FROM cluster_gc_candidates')
compose run --rm -T gc
after=$(compose exec -T metadata psql -U objectstore -d objectstore -At -c \
  'SELECT count(*) FROM cluster_gc_candidates')
[ "$before" = "$after" ]
first_gc=$(compose run --rm -T gc --apply)
printf '%s\n' "$first_gc" | grep -q '^orphan_candidates=[1-9]'
printf '%s\n' "$first_gc" | grep -q '^segments_deleted=0$'
second_gc=$(compose run --rm -T gc --apply)
printf '%s\n' "$second_gc" | grep -q '^segments_deleted=[1-9]'
run_phase recovered
python3 scripts/test-cluster-http.py "$env_file" version-survivor
run_phase verify-expanded
backup_dir=$(mktemp -d)
sh scripts/backup-cluster-metadata.sh "$env_file" "$backup_dir/metadata.dump"
compose --profile recovery up -d --wait metadata-recovery
compose exec -T metadata-recovery pg_restore -U objectstore -d objectstore --no-owner --no-acl \
  < "$backup_dir/metadata.dump"
compose stop metadata
compose run --rm -T --no-deps \
  -e 'POSTGRES_JDBC_URL=jdbc:postgresql://metadata:5432,metadata-recovery:5432/objectstore?connectTimeout=3&socketTimeout=10&targetServerType=primary&hostRecheckSeconds=0' \
  --entrypoint java gateway --add-modules jdk.httpserver,java.net.http \
  -cp /app:/app/postgresql.jar:/app/hash4j.jar cloud.lunarsky.store.ClusterIntegrationTest recovered
compose start metadata
wait_ready
echo 'Cluster failure tests passed'

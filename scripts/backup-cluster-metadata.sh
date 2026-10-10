#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
env_file=${1:?Usage: sh scripts/backup-cluster-metadata.sh /path/to/cluster.env /path/to/metadata.dump}
output=${2:?Usage: sh scripts/backup-cluster-metadata.sh /path/to/cluster.env /path/to/metadata.dump}
if [ -e "$output" ]; then
  echo "Backup destination already exists" >&2
  exit 1
fi
umask 077
temporary=$(mktemp "${output}.tmp.XXXXXX")
trap 'rm -f "$temporary"' EXIT
docker compose --env-file "$env_file" -f compose.cluster.yaml exec -T metadata \
  pg_dump -U objectstore -d objectstore --format=custom --no-owner --no-acl > "$temporary"
test -s "$temporary"
docker compose --env-file "$env_file" -f compose.cluster.yaml exec -T metadata \
  pg_restore --list < "$temporary" > /dev/null
mv "$temporary" "$output"
echo "Metadata backup written to $output"

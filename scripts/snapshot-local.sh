#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
mode=${1:?Usage: sh scripts/snapshot-local.sh single|cluster backup|restore ENV_FILE ARCHIVE}
action=${2:?Usage: sh scripts/snapshot-local.sh single|cluster backup|restore ENV_FILE ARCHIVE}
env_file=${3:?Usage: sh scripts/snapshot-local.sh single|cluster backup|restore ENV_FILE ARCHIVE}
archive=${4:?Usage: sh scripts/snapshot-local.sh single|cluster backup|restore ENV_FILE ARCHIVE}
[ -f "$env_file" ] || { echo "Environment file is missing" >&2; exit 1; }
encrypted_root=
while IFS= read -r line; do
  case "$line" in ENCRYPTED_STORAGE_ROOT=*) encrypted_root=${line#*=} ;; esac
done < "$env_file"
[ -z "$encrypted_root" ] || {
  echo "Encrypted bind-mount overlays are not covered by this volume snapshot tool" >&2
  exit 1
}

case "$mode" in
  single)
    compose=compose.yaml
    components='objectstore:/data'
    ;;
  cluster)
    compose=compose.cluster.yaml
    components='metadata:/var/lib/postgresql/data metadata-recovery:/var/lib/postgresql/data node-a:/data node-b:/data node-c:/data node-d:/data'
    ;;
  *) echo "Unknown snapshot mode" >&2; exit 1 ;;
esac
case "$action" in backup|restore) ;; *) echo "Unknown snapshot action" >&2; exit 1 ;; esac

if [ "$mode" = cluster ]; then
  running=$(docker compose --env-file "$env_file" -f "$compose" \
    --profile recovery --profile maintenance --profile automatic --profile expansion \
    ps --status running --services)
else
  running=$(docker compose --env-file "$env_file" -f "$compose" ps --status running --services)
fi
[ -z "$running" ] || { echo "Stop all services in this Compose project before $action: $running" >&2; exit 1; }

umask 077
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT HUP INT TERM
if [ "$action" = backup ]; then
  [ ! -e "$archive" ] || { echo "Snapshot already exists" >&2; exit 1; }
  printf '%s\n' "$mode" > "$stage/MODE"
  for component in $components; do
    service=${component%%:*}
    directory=${component#*:}
    docker compose --env-file "$env_file" -f "$compose" run --rm --no-deps \
      --entrypoint tar "$service" -C "$directory" -cf - . > "$stage/$service.tar"
    tar -tf "$stage/$service.tar" > /dev/null
  done
  (cd "$stage" && sha256sum ./*.tar > SHA256SUMS)
  temporary=$(mktemp "${archive}.tmp.XXXXXX")
  trap 'rm -f "$temporary"; rm -rf "$stage"' EXIT HUP INT TERM
  tar -C "$stage" -cf "$temporary" .
  mv "$temporary" "$archive"
  echo "Cold $mode snapshot written to $archive"
else
  [ -f "$archive" ] || { echo "Snapshot is missing" >&2; exit 1; }
  tar -tf "$archive" > /dev/null
  tar -C "$stage" -xf "$archive"
  [ "$(cat "$stage/MODE")" = "$mode" ] || { echo "Snapshot mode mismatch" >&2; exit 1; }
  (cd "$stage" && sha256sum -c SHA256SUMS)
  for component in $components; do
    service=${component%%:*}
    directory=${component#*:}
    [ -f "$stage/$service.tar" ] || { echo "Snapshot component missing: $service" >&2; exit 1; }
    docker compose --env-file "$env_file" -f "$compose" run --rm --no-deps \
      --entrypoint sh "$service" -c \
      'test -z "$(find "$1" -mindepth 1 -print -quit)"' sh "$directory" || {
        echo "Restore destination is not empty: $service" >&2
        exit 1
      }
  done
  for component in $components; do
    service=${component%%:*}
    directory=${component#*:}
    docker compose --env-file "$env_file" -f "$compose" run --rm --no-deps \
      --entrypoint tar "$service" -C "$directory" -xpf - < "$stage/$service.tar"
  done
  echo "Cold $mode snapshot restored; start services and run verification"
fi

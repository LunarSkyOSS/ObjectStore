#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

for command in docker openssl; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing prerequisite: $command" >&2
    exit 1
  fi
done
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose is required" >&2
  exit 1
fi

compose=(docker compose -f compose.yaml -f compose.admin.yaml -f compose.credentials.yaml)

refuse_custom_storage() {
  echo "This installer cannot safely reproduce the existing storage configuration." >&2
  echo "Update using your existing Docker Compose files and encryption overlays instead." >&2
  exit 1
}

compose_read() {
  S3_ACCESS_KEY="${S3_ACCESS_KEY:-ObjectStoreInstallCheck}" \
  S3_SECRET_KEY="${S3_SECRET_KEY:-ObjectStoreInstallCheckReadOnlyCredentials}" \
    "${compose[@]}" "$@"
}

custom_configuration=$(compose_read config --environment | awk -F= '
  $1 == "ENCRYPTED_STORAGE_ROOT" || $1 == "ENCRYPTED_VOLUME_ID" || $1 == "COMPOSE_FILE" {
    sub(/^[^=]*=/, ""); if (length($0)) print "configured"
  }')
[ -z "$custom_configuration" ] || refuse_custom_storage
existing=$(compose_read ps --all --quiet objectstore)
for container in $existing; do
  data_type=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Type}}{{end}}{{end}}' "$container")
  [ "$data_type" = volume ] || refuse_custom_storage
  configured_files=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$container")
  [ -n "$configured_files" ] || refuse_custom_storage
  IFS=',' read -r -a files <<< "$configured_files"
  for file in "${files[@]}"; do
    case "$file" in
      "$PWD/compose.yaml"|"$PWD/compose.admin.yaml"|"$PWD/compose.credentials.yaml") ;;
      *) refuse_custom_storage ;;
    esac
  done
  if [ ! -f secrets/managed-master ]; then
    managed=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" |
      awk -F= '$1 == "S3_MANAGED_KEYS_MASTER_FILE" {sub(/^[^=]*=/, ""); if (length($0)) print "configured"}')
    if [ -n "$managed" ]; then
      echo "Managed credentials are already configured, but secrets/managed-master is missing." >&2
      echo "Restore the original master key from backup; refusing to generate a replacement." >&2
      exit 1
    fi
  fi
done
if [ -n "$existing" ] && [ ! -f .env ]; then
  echo "An existing deployment was found, but .env is missing." >&2
  echo "Restore its original configuration; refusing to generate a replacement root key." >&2
  exit 1
fi

umask 077
if [ ! -e .env ]; then
  access="OS$(openssl rand -hex 12 | tr '[:lower:]' '[:upper:]')"
  secret="$(openssl rand -hex 32)"
  cat > .env <<EOF
S3_ACCESS_KEY=$access
S3_SECRET_KEY=$secret
S3_BUCKET=objects
S3_REGION=us-east-1
ADMIN_UI_BIND_IP=127.0.0.1
ADMIN_UI_HOST_PORT=9001
EOF
  echo "Created .env with a new root S3 key"
else
  echo "Keeping existing .env"
fi
mkdir -p secrets
chmod 700 secrets
if [ ! -e secrets/admin-password ]; then
  openssl rand -hex 32 > secrets/admin-password
  echo "Created secrets/admin-password"
fi
if [ ! -e secrets/managed-master ]; then
  openssl rand -hex 32 > secrets/managed-master
  echo "Created secrets/managed-master"
fi
chmod 644 secrets/admin-password secrets/managed-master

"${compose[@]}" up -d --build --wait
echo "S3 listener: $("${compose[@]}" port objectstore 9000)"
echo "Admin console: http://$("${compose[@]}" port objectstore 9001)/_objectstore/admin/login"
echo "Credentials are in .env and secrets/admin-password; keep secrets/managed-master for recovery."

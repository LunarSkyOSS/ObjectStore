#!/usr/bin/env bash
set -euo pipefail

root=$(mktemp -d)
trap 'rm -rf "$root"' EXIT
mkdir -p "$root/project/scripts" "$root/bin"
cp "$(dirname "$0")/install.sh" "$root/project/scripts/install.sh"
cat > "$root/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$INSTALL_TEST_CALLS"
if [ "$1" = inspect ]; then
  if [[ "$3" == *Mounts* ]]; then printf '%s\n' "${INSTALL_TEST_MOUNT:-volume}";
  elif [[ "$3" == *Config.Env* ]]; then printf '%s\n' "${INSTALL_TEST_MANAGED:-}";
  else printf '%s\n' "${INSTALL_TEST_FILES:-$PWD/compose.yaml}"; fi
  exit 0
fi
case "$*" in
  'compose version') ;;
  *'config --environment') printf '%s\n' "${INSTALL_TEST_ENV:-}" ;;
  *'ps --all --quiet objectstore') printf '%s\n' "${INSTALL_TEST_CONTAINER:-}" ;;
  *'up -d --build --wait') touch "$INSTALL_TEST_MUTATION" ;;
  *'port objectstore 9000') echo 127.0.0.1:9000 ;;
  *'port objectstore 9001') echo 127.0.0.1:9001 ;;
  *) echo "Unexpected Docker command: $*" >&2; exit 2 ;;
esac
EOF
cat > "$root/bin/openssl" <<'EOF'
#!/usr/bin/env bash
touch "$INSTALL_TEST_MUTATION"
printf '%064d\n' 1
EOF
chmod +x "$root/bin/docker" "$root/bin/openssl"
export PATH="$root/bin:$PATH"
export INSTALL_TEST_CALLS="$root/calls" INSTALL_TEST_MUTATION="$root/mutated"

refused() {
  rm -f "$INSTALL_TEST_MUTATION" "$INSTALL_TEST_CALLS"
  if bash "$root/project/scripts/install.sh" > "$root/output" 2>&1; then
    echo "Installer accepted unsafe storage configuration" >&2
    exit 1
  fi
  test ! -e "$INSTALL_TEST_MUTATION"
  test ! -e "$root/project/.env"
  test ! -e "$root/project/secrets"
  grep -q 'existing Docker Compose files' "$root/output"
}

export INSTALL_TEST_ENV='ENCRYPTED_STORAGE_ROOT=/encrypted/objectstore'
refused
export INSTALL_TEST_ENV='ENCRYPTED_VOLUME_ID=0123456789abcdef0123456789abcdef'
refused
export INSTALL_TEST_ENV='COMPOSE_FILE=compose.yaml:compose.encrypted.yaml'
refused
export INSTALL_TEST_ENV='' INSTALL_TEST_CONTAINER=existing-container INSTALL_TEST_MOUNT=bind
refused
export INSTALL_TEST_MOUNT=volume INSTALL_TEST_FILES="$root/project/compose.yaml,$root/project/custom.yaml"
refused

export INSTALL_TEST_FILES="$root/project/compose.yaml"
export INSTALL_TEST_ENV=$'ENCRYPTED_STORAGE_ROOT=\nENCRYPTED_VOLUME_ID='
export INSTALL_TEST_MANAGED='S3_MANAGED_KEYS_MASTER_FILE=/run/secrets/objectstore_managed_master'
if bash "$root/project/scripts/install.sh" > "$root/output" 2>&1; then
  echo 'Installer replaced the missing master key' >&2
  exit 1
fi
test ! -e "$INSTALL_TEST_MUTATION"
test ! -e "$root/project/.env"
test ! -e "$root/project/secrets"
grep -q 'Restore the original master key' "$root/output"

export INSTALL_TEST_MANAGED=''
if bash "$root/project/scripts/install.sh" > "$root/output" 2>&1; then
  echo 'Installer replaced the missing root configuration' >&2
  exit 1
fi
test ! -e "$INSTALL_TEST_MUTATION"
test ! -e "$root/project/.env"
test ! -e "$root/project/secrets"
grep -q 'Restore its original configuration' "$root/output"

mkdir "$root/project/secrets"
printf '# ENCRYPTED_STORAGE_ROOT=/old-comment\nENCRYPTED_STORAGE_ROOT=\n' > "$root/project/.env"
printf 'existing-admin-password\n' > "$root/project/secrets/admin-password"
printf '%064d\n' 2 > "$root/project/secrets/managed-master"
cp "$root/project/.env" "$root/original.env"
cp "$root/project/secrets/admin-password" "$root/original-password"
cp "$root/project/secrets/managed-master" "$root/original-master"
bash "$root/project/scripts/install.sh" > "$root/output" 2>&1
test -e "$INSTALL_TEST_MUTATION"
cmp "$root/project/.env" "$root/original.env"
cmp "$root/project/secrets/admin-password" "$root/original-password"
cmp "$root/project/secrets/managed-master" "$root/original-master"
echo 'Installer safety tests passed'

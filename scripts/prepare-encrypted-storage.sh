#!/bin/sh
set -eu

mode=${1:-}
case "$mode" in
  single) directories='single' ;;
  cluster) directories='cluster/gateway-staging cluster/metadata cluster/metadata-recovery cluster/repair-staging cluster/gc-staging cluster/maintenance-staging cluster/node-a cluster/node-b cluster/node-c cluster/node-d' ;;
  *) echo 'Usage: prepare-encrypted-storage.sh single|cluster [environment-file]' >&2; exit 2 ;;
esac

if [ "$#" -gt 2 ]; then
  echo 'Too many arguments' >&2
  exit 2
fi
if [ "$#" -eq 2 ]; then
  [ -r "$2" ] || { echo 'Environment file is not readable' >&2; exit 2; }
  while IFS='=' read -r name value || [ -n "${name:-}" ]; do
    case "$name" in
      ENCRYPTED_STORAGE_ROOT) ENCRYPTED_STORAGE_ROOT=$value ;;
      ENCRYPTED_VOLUME_ID) ENCRYPTED_VOLUME_ID=$value ;;
    esac
  done < "$2"
fi

root=${ENCRYPTED_STORAGE_ROOT:?Set ENCRYPTED_STORAGE_ROOT to an existing mounted LUKS directory}
id=${ENCRYPTED_VOLUME_ID:?Set ENCRYPTED_VOLUME_ID to 32 lowercase hex characters}
case "$id" in *[!0-9a-f]*|'') echo 'ENCRYPTED_VOLUME_ID must be lowercase hex' >&2; exit 2 ;; esac
[ "${#id}" -eq 32 ] || { echo 'ENCRYPTED_VOLUME_ID must be 32 characters' >&2; exit 2; }
[ -d "$root" ] && [ ! -L "$root" ] || { echo 'Encrypted root is missing or a symlink' >&2; exit 1; }
root=$(realpath -e "$root")
source=$(findmnt -n -M "$root" -o SOURCE) || { echo 'Encrypted root is not a mount point' >&2; exit 1; }
device=$(printf '%s\n' "$source" | sed 's/\[.*$//')
case "$device" in /dev/*) ;; *) echo 'Encrypted root must be backed by a block device' >&2; exit 1 ;; esac
lsblk -s -n -r -o TYPE "$device" | grep -Fxq crypt || {
  echo 'Encrypted root is not backed by an active dm-crypt mapping' >&2
  exit 1
}

for relative in $directories; do
  path=$root/$relative
  [ ! -L "$root/cluster" ] || { echo 'Refusing symlink under encrypted root' >&2; exit 1; }
  [ ! -L "$path" ] || { echo "Refusing symlink: $path" >&2; exit 1; }
  if [ -d "$path" ] && [ ! -e "$path/.objectstore-encrypted" ] &&
     [ -n "$(find "$path" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo "Refusing to mark nonempty directory: $path" >&2
    exit 1
  fi
  install -d -m 0700 "$path"
  [ "$(findmnt -n -T "$path" -o TARGET)" = "$root" ] || {
    echo "Directory is not on the encrypted mount: $path" >&2
    exit 1
  }
  marker=$path/.objectstore-encrypted
  if [ -e "$marker" ]; then
    [ ! -L "$marker" ] && [ "$(cat "$marker")" = "$id" ] || {
      echo "Encrypted volume marker mismatch: $path" >&2
      exit 1
    }
  else
    printf '%s\n' "$id" > "$marker"
  fi
  chmod 0600 "$marker"
  case "$relative" in
    cluster/metadata|cluster/metadata-recovery) chown 70:70 "$path" "$marker" ;;
    *) chown 10001:10001 "$path" "$marker" ;;
  esac
done

echo "Prepared $mode paths on the active encrypted mount: $root"

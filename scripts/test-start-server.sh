#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT HUP INT TERM
cat > "$test_dir/java" <<'EOF'
#!/bin/sh
printf '%s\n' "$@" > "$OBJECTSTORE_TEST_ARGS"
EOF
chmod +x "$test_dir/java"
OBJECTSTORE_TEST_ARGS="$test_dir/args" PATH="$test_dir:$PATH" \
  OBJECTSTORE_REQUEST_TIMEOUT_SECONDS=300 OBJECTSTORE_RESPONSE_TIMEOUT_SECONDS=45 \
  sh scripts/start-server.sh
grep -Fx -- '-Dsun.net.httpserver.maxReqTime=300' "$test_dir/args" >/dev/null
grep -Fx -- '-Dsun.net.httpserver.maxRspTime=45' "$test_dir/args" >/dev/null
if OBJECTSTORE_TEST_ARGS="$test_dir/args" PATH="$test_dir:$PATH" \
  OBJECTSTORE_REQUEST_TIMEOUT_SECONDS=86401 sh scripts/start-server.sh 2>/dev/null; then
  echo 'Oversized request timeout was accepted' >&2
  exit 1
fi
echo 'Server launcher timeout tests passed'

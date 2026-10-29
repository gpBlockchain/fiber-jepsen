#!/usr/bin/env bash
# Checks the host prerequisites for the scheduled self-hosted chaos workflow.
# It intentionally does not install anything; install missing packages yourself.
set -euo pipefail

missing=()
for c in docker git curl tar python3 ssh-keygen; do
  command -v "$c" >/dev/null 2>&1 || missing+=("$c")
done
if ! command -v nc >/dev/null 2>&1 && ! command -v netcat >/dev/null 2>&1; then
  missing+=("netcat/nc")
fi

if ((${#missing[@]})); then
  echo "missing prerequisites: ${missing[*]}" >&2
  exit 1
fi

python3 - <<'PY'
import sys
try:
    import yaml  # noqa: F401
except Exception as e:
    sys.exit(f"python3 PyYAML is required by make-snapshot.sh: {e}")
PY

docker info >/dev/null
docker compose version >/dev/null

avail_kb="$(df -Pk . | awk 'NR == 2 {print $4}')"
required_kb=$((30 * 1024 * 1024))
if ((avail_kb < required_kb)); then
  echo "need at least 30 GiB free, found $((avail_kb / 1024 / 1024)) GiB in $(pwd)" >&2
  exit 1
fi

echo "== host prerequisites OK"
echo "docker: $(docker --version)"
echo "docker compose: $(docker compose version --short)"
echo "free disk: $((avail_kb / 1024 / 1024)) GiB"

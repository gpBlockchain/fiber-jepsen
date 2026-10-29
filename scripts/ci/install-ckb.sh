#!/usr/bin/env bash
# Downloads the ckb and ckb-cli binaries used by make-snapshot.sh.
# Usage: scripts/ci/install-ckb.sh [destination]
set -euo pipefail

CKB_VERSION="${CKB_VERSION:-0.210.0}"
CKB_CLI_VERSION="${CKB_CLI_VERSION:-2.0.0}"
dest="${1:-${RUNNER_TEMP:-/tmp}/chaos-bin}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

mkdir -p "$dest"

echo "== downloading ckb ${CKB_VERSION}"
curl -fL --retry 3 --retry-delay 2 \
  -o "$tmp/ckb.tar.gz" \
  "https://github.com/nervosnetwork/ckb/releases/download/v${CKB_VERSION}/ckb_v${CKB_VERSION}_x86_64-unknown-linux-gnu.tar.gz"
tar -xzf "$tmp/ckb.tar.gz" -C "$dest" --strip-components=1

echo "== downloading ckb-cli ${CKB_CLI_VERSION}"
curl -fL --retry 3 --retry-delay 2 \
  -o "$tmp/ckb-cli.tar.gz" \
  "https://github.com/nervosnetwork/ckb-cli/releases/download/v${CKB_CLI_VERSION}/ckb-cli_v${CKB_CLI_VERSION}_x86_64-unknown-linux-gnu.tar.gz"
tar -xzf "$tmp/ckb-cli.tar.gz" -C "$dest" --strip-components=1

chmod +x "$dest/ckb" "$dest/ckb-cli"
"$dest/ckb" --version
"$dest/ckb-cli" --version

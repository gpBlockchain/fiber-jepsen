#!/usr/bin/env bash
# Builds a compact CI artifact:
#   - all of results/ (logs + SUMMARY.md)
#   - only the store directories of cases that did not pass
#   - optionally the whole store when UPLOAD_STORE=true
#
# Usage: scripts/ci/pack-results.sh [results-dir] [output.tgz]
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
RESULTS="${1:-results}"
OUT="${2:-chaos-results.tgz}"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/results"
cp -a "$RESULTS"/. "$STAGE/results/"

if [[ "${UPLOAD_STORE:-false}" == "true" ]]; then
  cp -a store "$STAGE/store"
else
  for log in "$RESULTS"/C*.log; do
    [[ -f "$log" ]] || continue
    verdict="$(grep -oE 'Everything looks good|Analysis invalid|Errors occurred during analysis|Jepsen broke' "$log" | tail -1 || true)"
    [[ "$verdict" == "Everything looks good" ]] && continue
    store="$(grep -o 'Wrote /jepsen/store/[^ ]*' "$log" | tail -1 | sed 's#Wrote /jepsen/store/##; s#/results.edn##' || true)"
    [[ -n "$store" && -d "store/$store" ]] || continue
    mkdir -p "$STAGE/store/$(dirname "$store")"
    cp -a "store/$store" "$STAGE/store/$store"
  done
fi

if [[ -d "$STAGE/store" ]]; then
  tar -czf "$OUT" -C "$STAGE" results store
else
  tar -czf "$OUT" -C "$STAGE" results
fi

echo "artifact: $OUT ($(du -h "$OUT" | cut -f1))"

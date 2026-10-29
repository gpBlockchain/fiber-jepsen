#!/usr/bin/env bash
# Runs the chaos suite in CI. Results go to results/ (or $2).
#
#   scripts/ci/run-suite.sh smoke
#   scripts/ci/run-suite.sh matrix
#   scripts/ci/run-suite.sh full
#
# "full" adds the 8-round nonce regression. C04/C10 are currently known to
# fail on long partitions; set ALLOW_KNOWN_FAILURES=false to gate on them too.
set -uo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
SUITE="${1:-full}"
OUT="${2:-$ROOT/results}"
REPEAT_ROUNDS="${REPEAT_ROUNDS:-8}"
KNOWN_FAILURES="${KNOWN_FAILURES:-C04-partition C10-combo}"
ALLOW_KNOWN_FAILURES="${ALLOW_KNOWN_FAILURES:-true}"
LOG=(--log-level "info,fnn::fiber::channel=debug")
ALL="$OUT/all.log"
failures=()

mkdir -p "$OUT"
: >"$ALL"

run_case() {
  local name="$1"
  shift
  local log="$OUT/$name.log"
  local rc verdict store

  echo "== $name ($(date -u +%H:%M) UTC)" | tee -a "$ALL"
  "$ROOT/scripts/run.sh" "$@" "${LOG[@]}" >"$log" 2>&1
  rc=$?
  verdict="$(grep -oE 'Everything looks good|Analysis invalid|Errors occurred during analysis|Jepsen broke' "$log" | tail -1 || true)"
  store="$(grep -o 'Wrote /jepsen/store/[^ ]*' "$log" | tail -1 | sed 's#Wrote /jepsen/store/##; s#/results.edn##' || true)"

  printf '   exit=%s verdict=%s store=%s\n' "$rc" "${verdict:-none}" "${store:-none}" | tee -a "$ALL"

  if [[ "$verdict" != "Everything looks good" ]]; then
    if [[ "$verdict" == "Analysis invalid" &&
          " $KNOWN_FAILURES " == *" $name "* &&
          "$ALLOW_KNOWN_FAILURES" == "true" ]]; then
      echo "   known failure (allowed): $name" | tee -a "$ALL"
    else
      failures+=("$name")
    fi
  fi
}

# C01a is the one-node kill case; the old command omitted --kill-targets and
# therefore used the default one,all and actually killed all three nodes.
run_case C01a-kill9-one --time-limit 120 --faults kill --kill-targets one

if [[ "$SUITE" != "smoke" ]]; then
  run_case C01b-kill9-all --time-limit 120 --faults kill --kill-targets all --rate 20 --nemesis-interval 8 --quiesce 60
  run_case C02a-term-one --time-limit 120 --faults term --workload invoice
  run_case C02b-term-all --time-limit 120 --faults term --term-targets all
  run_case C03-pause --time-limit 120 --faults pause
  run_case C04-partition --time-limit 120 --faults partition
  run_case C05a-disconnect --time-limit 120 --faults disconnect --workload invoice
  run_case C05b-disconnect-hf --time-limit 120 --faults disconnect --disconnect-targets all --nemesis-interval 2 --workload invoice
  run_case C05c-disconnect-auto --time-limit 120 --faults disconnect --disconnect-reconnect auto
  run_case C06-latency-loss --time-limit 120 --faults latency,loss --workload mixed --net-loss 20
  run_case C07-ckb-partition --time-limit 120 --faults ckb-partition --ckb-targets all
  run_case C08-ckb-latency --time-limit 120 --faults ckb-latency --ckb-delay 3000 --ckb-jitter 1000
  run_case C09-clock --time-limit 120 --faults clock --clock-skew-max 300
  run_case C10-combo --time-limit 300 --faults kill,pause,partition,disconnect --rate 10 --workload mixed
fi

if [[ "$SUITE" == "full" ]]; then
  repeat_log="$OUT/repeat-nonce.log"
  echo "== repeat-nonce ${REPEAT_ROUNDS} rounds ($(date -u +%H:%M) UTC)" | tee -a "$ALL"
  "$ROOT/scripts/repeat.sh" "$REPEAT_ROUNDS" --time-limit 60 --faults kill \
    --kill-targets all --rate 20 --nemesis-interval 8 --quiesce 60 "${LOG[@]}" \
    >"$repeat_log" 2>&1
  repeat_rc=$?
  passed="$(grep -c 'Everything looks good' "$repeat_log" || true)"
  printf '   exit=%s passed=%s/%s\n' "$repeat_rc" "$passed" "$REPEAT_ROUNDS" | tee -a "$ALL"

  if ((repeat_rc != 0 || passed != REPEAT_ROUNDS)); then
    failures+=("repeat-nonce ($passed/$REPEAT_ROUNDS passed)")
  fi

  {
    while IFS= read -r d; do
      python3 "$ROOT/scripts/stall_report.py" "$ROOT/store/$d"
    done < <(grep -o 'store: .*' "$repeat_log" | sed 's/store: //' || true)
  } >"$OUT/stall_report.txt" 2>&1 || true

  {
    echo "== repeat-nonce ($(date -u +%H:%M) UTC)"
    cat "$repeat_log"
  } >"$OUT/repeat-nonce-labeled.log"
  python3 "$ROOT/scripts/jepsen_tally.py" "$OUT/repeat-nonce-labeled.log" "$ROOT/store" \
    >"$OUT/repeat-tally.txt" 2>&1 || true
fi

if ((${#failures[@]})); then
  printf 'FAILED: %s\n' "${failures[*]}" | tee -a "$ALL"
  exit 1
fi

echo "ALL EXPECTED CASES DONE" | tee -a "$ALL"

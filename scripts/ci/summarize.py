#!/usr/bin/env python3
"""Writes a Markdown run summary for the CI workflow.

    python3 scripts/ci/summarize.py results > results/SUMMARY.md
"""
import os
import pathlib
import re
import sys


def section(text, key):
    m = re.search(re.escape(key) + r"\s+\[", text)
    if not m:
        return ""
    start = m.end() - 1
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "[":
            depth += 1
        elif text[i] == "]":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return ""


def one(text, key):
    m = re.search(re.escape(key) + r"\s+([^,\s}]+)", text)
    return m.group(1) if m else ""


def main():
    root = pathlib.Path.cwd()
    results = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "results")
    rows = []

    for log in sorted(results.glob("C*.log")):
        text = log.read_text(errors="replace")
        verdicts = re.findall(
            r"Everything looks good|Analysis invalid|Errors occurred during analysis|Jepsen broke",
            text,
        )
        verdict = verdicts[-1] if verdicts else "no verdict"
        stores = re.findall(r"Wrote /jepsen/store/(\S+)", text)
        store = stores[-1].replace("/results.edn", "") if stores else ""
        edn_path = root / "store" / store / "results.edn" if store else None

        valid = summary = payments = ok = chproblems = unfinished = probes = "—"
        if edn_path and edn_path.exists():
            edn = edn_path.read_text(errors="replace")
            valid_match = re.findall(r":valid\? ([^,\n}]+)", edn)
            valid = valid_match[-1].strip() if valid_match else "?"
            summary_match = re.search(r":channel-summary \{([^}]*)\}", edn)
            summary = summary_match.group(1).strip() if summary_match else "—"
            payments = one(edn, ":payments")
            ok = one(edn, ":payments-ok")
            chproblems = str(section(edn, ":channel-problems").count("{:channel"))
            unfinished = str(section(edn, ":unfinished-payments").count("{:node"))
            probes = str(section(edn, ":probe-failures").count("{:from"))

        rows.append(
            (log.stem, verdict, valid, payments, ok, summary, chproblems, unfinished, probes)
        )

    print("# fiber-jepsen CI summary\n")
    commit_file = results / "fiber-commit.txt"
    if commit_file.exists():
        print(f"Fiber commit: `{commit_file.read_text().strip()}`\n")

    if rows:
        print("| Case | Verdict | :valid? | Payments | OK | Channels | Channel problems | Unfinished | Probe failures |")
        print("|---|---|---|---:|---:|---|---:|---:|---:|")
        for r in rows:
            print("| {} | {} | {} | {} | {} | {} | {} | {} | {} |".format(*r))
    else:
        print("No case logs found.")

    repeat = results / "repeat-nonce.log"
    if repeat.exists():
        lines = repeat.read_text(errors="replace").splitlines()
        runs = [l for l in lines if re.match(r"run \d+/\d+:", l)]
        passed = sum("Everything looks good" in l for l in runs)
        print(f"\n## Nonce regression\n")
        print(f"- rounds: {len(runs)}")
        print(f"- passed: {passed}")
        tally = results / "repeat-tally.txt"
        if tally.exists():
            print("\n```text")
            print(tally.read_text(errors="replace").strip())
            print("```")
        stalled = results / "stall_report.txt"
        if stalled.exists():
            text = stalled.read_text(errors="replace")
            print(f"\nstall_report: `{text.count('no channel problems')}` clean, "
                  f"`{text.count('stalled:')}` stalled, `{text.count('closed:')}` closed")


if __name__ == "__main__":
    main()

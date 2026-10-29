# fiber-jepsen

[Jepsen](https://github.com/jepsen-io/jepsen) tests for [Fiber](https://github.com/nervosnetwork/fiber), the payment channel network on Nervos CKB.

Fiber's unit tests drive nodes in-process, and its integration tests replay scripted scenarios. This suite does something different. It runs real `fnn` binaries on separate hosts and sends concurrent payments through them while injecting faults: crashes, graceful restarts, pauses, network partitions, latency and loss, CKB disconnects and delays, and clock skew. It then checks invariants over the final state of every node.

**Status: early work in progress.** The first milestone is one end-to-end run (payments, `kill -9`, invariant check) against a CKB devnet.

## What it checks

After all faults are healed and the network has had time to converge:

- every channel opened during setup is still `ChannelReady`, with no pending TLCs;
- both ends of a channel agree on its balances (A's local balance equals B's remote balance);
- no channel's total (local + remote + in-flight TLCs) changed: payments move money within a channel, they never create or destroy it;
- every payment reached `Success` or `Failed`;
- no payment the client saw succeed reports a different status later.

## Faults

| Fault | How |
|---|---|
| `kill` | `SIGKILL` on one node or all nodes, then restart. A crash can land in the middle of a write. |
| `term` | `SIGTERM` on one node (`--term-targets one,all`), wait up to `--term-timeout` (30 s) for fnn to exit, `SIGKILL` if it doesn't, then restart. A separate case from `kill`: the checker fails the run if any graceful shutdown timed out and reports exit times under `:term-exits`. |
| `pause` | `SIGSTOP` / `SIGCONT` on one node. The peer stays connected but gets no replies. |
| `partition` | iptables drops traffic between one node and the others. |
| `ckb-partition` | iptables drops traffic between one or all fnn nodes (`--ckb-targets`) and the CKB `chain` container. CKB is treated as an external dependency. |
| `ckb-latency` | tc netem delay + jitter (`--ckb-delay 3000 --ckb-jitter 1000`, ms) on fnn -> chain traffic only. |
| `latency` | tc netem delay, jitter and reorder (`--net-delay 500 --net-jitter 200 --net-reorder 10`) on fnn -> fnn traffic of one or all nodes. |
| `loss` | tc netem packet loss (`--net-loss 10`, %) on fnn -> fnn traffic. |
| `disconnect` | fnn's own `disconnect_peer {pubkey}` RPC on a random node, for one or all of its peers (`--disconnect-targets one,all`). Heal re-dials each channel edge that lost its peer with `connect_peer {address}` (`--disconnect-reconnect rpc`, default; the RPC docs don't promise auto-reconnect) or leaves it to fnn (`auto`). The final heal always reconnects via RPC. Lower `--nemesis-interval` for rapid disconnect cycles. |
| `clock` | Per-process wall-clock skew with libfaketime, up to +/-`--clock-skew-max` (60 s). fnn is started with `LD_PRELOAD=libfaketime` only when `clock` is enabled; the offset lives in `/fiber/faketime` and is re-read on every call, so it changes without a restart. Monotonic clocks are not faked. |

The tc faults install one `prio` qdisc per node with a netem band for chain traffic and one for peer traffic, so CKB and peer shaping can overlap. The node image already has iproute2, iptables and faketime, and runs with `NET_ADMIN`.

## Workloads

| `--workload` | Payments |
|---|---|
| `keysend` (default) | keysend from each node to a random other node. |
| `invoice` | the receiver creates an invoice (`new_invoice`, currency `--invoice-currency`, default `Fibd`), the payer pays it with `send_payment {invoice}`. n1 pays n3 and n3 pays n1, so every payment is forwarded by n2 in both directions. |
| `mixed` | each payment picks keysend or invoice at random. |

For invoice payments the final read also fetches the receiver's invoice (`get_invoice`), and the checker reports `:invoice-mismatches`: a `Success` payment whose invoice isn't `Paid`, or a `Failed` payment whose invoice is.

The test matrix (topology x workload x fault -> invariants) is in [docs/chaos-plan.md](docs/chaos-plan.md).

```bash
scripts/run.sh --time-limit 120 --faults term --workload invoice
scripts/run.sh --time-limit 120 --faults ckb-partition,ckb-latency --ckb-targets all
scripts/run.sh --time-limit 120 --faults latency,loss --workload mixed --net-loss 20
scripts/run.sh --time-limit 120 --faults disconnect --nemesis-interval 2 --workload invoice
scripts/run.sh --time-limit 120 --faults clock --clock-skew-max 300
```

## Layout

- `docker/`: a CKB devnet (`chain`), three fnn nodes (`n1`–`n3`) and the Jepsen control node, on a private network `10.77.0.0/24`.
- `scripts/make-snapshot.sh`: creates the devnet with Fiber's own `tests/deploy/init-dev-chain.sh` (funded wallets, contracts, UDT), then stages it with the binaries into `docker/build/`. Every test starts from this snapshot.
- `src/jepsen/fiber/`:
  - `db.clj` (process lifecycle)
  - `topology.clj` (opens channels n1 - n2 - n3)
  - `client.clj` (keysend and invoice payments, final read)
  - `nemesis.clj` (term, CKB, tc netem and clock faults)
  - `checker.clj` (invariants)

## Running

On Linux or WSL2, with Docker, a Fiber checkout, ckb 0.210.0 and ckb-cli 2.0.0 in `PATH`:

```bash
# once: build fnn and the devnet snapshot
cargo build --release -p fiber-bin          # in your Fiber checkout
FNN_BIN=/path/to/fiber/target/release/fnn FIBER_SNAP=/path/to/a/clean/fiber/checkout \
  scripts/make-snapshot.sh

# each run starts from a fresh devnet
# --kill-targets defaults to one,all (random); use one for a true single-node kill
scripts/run.sh --time-limit 60 --faults kill --kill-targets one
scripts/run.sh --time-limit 300 --faults kill,pause,partition --rate 10

# several runs, one summary line each: verdict, post-heal probe, channel states, store directory
scripts/repeat.sh 4 --time-limit 60 --faults kill --kill-targets all --rate 20 --nemesis-interval 8 --quiesce 60
```

Results, including node logs and a timeline, are written to `store/`. To test another fnn build, copy it over `docker/build/bin/fnn`; `run.sh` rebuilds the node image.

`scripts/stall_report.py store/<test>/<run> ...` lists, for each channel with a problem, whether it is stalled on an unbalanced revocation nonce ledger ([#1561](https://github.com/nervosnetwork/fiber/issues/1561)), closed, or fine but still holding TLCs. It needs channel debug logs: add `--log-level 'info,fnn::fiber::channel=debug'` to the run.

### Scheduled chaos on a self-hosted runner

`.github/workflows/chaos-scheduled.yml` runs the suite on a self-hosted Linux
runner, daily at 02:00 Asia/Shanghai by default. It can also be started with
**Run workflow**.

Runner requirements:

- Linux x86_64, Docker and `docker compose`, the runner user in the `docker` group;
- at least 30 GiB free disk and 8 GiB RAM;
- `git`, `curl`, `tar`, `python3` with PyYAML, `nc`, `ssh-keygen`;
- Rust stable plus `cc`, `pkg-config`, OpenSSL and `clang` headers for the
  `tests/deploy/udt-init` helper run by `make-snapshot.sh` (the workflow
  installs Rust with rustup if `cargo` is missing, but not the system packages);
- network access to GitHub, Docker Hub and `ghcr.io`.

`ckb` and `ckb-cli` are downloaded by the workflow; they do not need to be
installed on the host. fnn is built in a `rust:1.95.0-bookworm` container
because the node image is Debian 12 and a host build on Ubuntu 24.04 would
require a newer glibc.

Labels default to `self-hosted`, `Linux`, `X64`. To use different labels, set
the repository variable `CHAOS_RUNNER_LABELS` to a JSON array, for example
`["self-hosted","Linux","X64","chaos"]`.

Inputs:

| Input | Default | Meaning |
|---|---|---|
| `fiber_ref` | `develop` | Fiber branch or tag to build |
| `suite` | `full` | `smoke` (C01a), `matrix` (C01a-C10), `full` (matrix + 8 repeat rounds) |
| `repeat_rounds` | `8` | nonce regression rounds for `full` |
| `allow_known_failures` | `true` | do not fail on the known C04/C10 failures |

`C04-partition` and `C10-combo` are allow-listed by default because the test's
partition can exceed fnn's 30s peer-response timeout; unexpected failures fail
the job. The workflow uploads `results/` and `store/` as artifacts, writes a
summary to the job page, and removes the devnet/build artifacts afterwards.

Scheduled workflows only run from the repository's default branch. If this
workflow lives on a feature branch, use **Run workflow** on that branch or merge
the workflow to the default branch. Because a self-hosted runner executes code
from the repository, do not enable this workflow for untrusted pull requests.

### Mixed versions, and a tally over several batches

To run n1 on a different fnn build than n2 and n3, copy that build to `docker/build/bin/fnn-n1` and point `COMPOSE_FILE` at the override:

```bash
COMPOSE_FILE=docker-compose.yml:docker-compose.mixed.yml   scripts/repeat.sh 4 --time-limit 60 --faults kill --kill-targets all --rate 20 --nemesis-interval 8   --quiesce 60 --log-level 'info,fnn::fiber::channel=debug'
```

`scripts/jepsen_tally.py <repeat-log> store` summarises several `repeat.sh` batches from one log, each introduced by a line `== <label> (HH:MM UTC)`. Per batch it counts the runs the checker passed, the runs with a stalled or a closed channel, the runs with a ready channel that still holds a TLC, how often the re-sync completion ran, and bad signatures. Run it where the store directories can be read as written (it calls `stall_report.py`).

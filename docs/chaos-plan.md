# Fiber 混沌测试计划（黑盒集成：压测 + 故障注入）

被测项目：[nervosnetwork/fiber](https://github.com/nervosnetwork/fiber)

思路：从集成角度无法判断节点处于 P2P 或通道的哪个阶段，因此不依赖内部状态。在持续压测负载下注入外部故障，让故障随机落在各个阶段，靠足够多的轮次覆盖；故障移除后，只通过 RPC 检查不变量。

一句话概括：**拓扑 × 负载 × 故障 → 恢复后检查断言**。

```mermaid
mindmap
  root((Fiber 混沌测试))
    在哪测 拓扑
      三跳 n1-n2-n3 已实现
      直连 / 网状 / watchtower / CCH 规划
    加什么压 负载
      keysend 已实现
      invoice 多跳 双向 已实现
      mixed 已实现
      MPP / 通道开关 规划
    注什么故障
      进程 kill -9 / kill / 暂停
      节点间 分区 / 延迟 / 丢包
      RPC 主动断开 disconnect_peer
      CKB 断连 / 延迟
      时钟偏移
    怎么判对错
      资金守恒 两端一致
      支付收敛 无卡住
      invoice 两端一致
      优雅退出及时
```

## 1. 拓扑

| 拓扑 | 状态 | 说明 |
|---|---|---|
| 三跳 n1 - n2 - n3 | 已实现 | 多跳支付经 n2 转发，可测中间节点故障 |
| 直连 A-B | 规划 | 最基本的通道恢复 |
| 网状 4 到 6 节点 | 规划 | 分区与重新路由 |
| 带 watchtower / CCH | 规划 | 需要额外容器 |

## 2. 负载（`--workload`）

| 取值 | 内容 |
|---|---|
| `keysend` | 每个节点向随机节点发 keysend |
| `invoice` | 收款方 `new_invoice`，付款方 `send_payment {invoice}`；n1→n3、n3→n1 双向，全部经 n2 转发 |
| `mixed` | 每笔随机选 keysend 或 invoice |

强度由 `--rate`（每秒支付数）控制。

## 3. 故障（`--faults`）

| 故障 | 注入方式 | 作用对象 |
|---|---|---|
| `kill` | SIGKILL 后重启（kill -9） | 一个或全部节点 |
| `term` | SIGTERM，等待 `--term-timeout` 秒退出，超时则 SIGKILL，然后重启（kill） | 一个或全部节点 |
| `pause` | SIGSTOP / SIGCONT | 一个节点 |
| `partition` | iptables 隔离节点间流量 | 一个节点与其余节点 |
| `latency` | tc netem 延迟 + 抖动 + 乱序 | 节点间流量 |
| `loss` | tc netem 丢包 | 节点间流量 |
| `ckb-partition` | iptables 断开 fnn 与 CKB chain 容器 | 一个或全部节点 |
| `ckb-latency` | tc netem 延迟 + 抖动，仅作用于 fnn→CKB | 一个或全部节点 |
| `disconnect` | 调用 `disconnect_peer {pubkey}` 主动断开某节点的一个或全部 peer；恢复时用 `connect_peer {address}` 重连（`--disconnect-reconnect rpc`，默认）或等 fnn 自动重连（`auto`） | 一个节点的一个或全部 peer |
| `clock` | libfaketime 按进程偏移墙上时钟（±`--clock-skew-max` 秒） | 一个或全部节点 |

CKB 作为外部依赖，只注入断连和延迟两类。

## 4. 断言（故障全部恢复、等待 `--quiesce` 后）

1. 初始通道仍为 `ChannelReady`，没有残留 pending TLC
2. 两端余额一致（A 的 local = B 的 remote）
3. 每个通道总额不变（资金守恒）
4. 所有支付最终为 `Success` 或 `Failed`，没有一直 Inflight
5. 客户端看到成功的支付之后不会变成别的状态
6. 恢复后每个通道双向的探测支付成功
7. invoice 支付：付款方 `Success` ⇔ 收款方 invoice 为 `Paid`（新增）
8. `term`：fnn 在超时内优雅退出（新增，结果见 `:term-exits`）

## 5. 测试矩阵（拓扑均为三跳）

| # | 负载 | 故障 | 重点断言 | 命令 |
|---|---|---|---|---|
| 1 | keysend | kill | 1-6 | `--faults kill` |
| 2 | keysend | term | 1-6, 8 | `--faults term` |
| 3 | invoice | kill（打中间节点） | 1-7 | `--faults kill --workload invoice` |
| 4 | invoice | term | 1-8 | `--faults term --workload invoice` |
| 5 | mixed | pause | 1-7 | `--faults pause --workload mixed` |
| 6 | mixed | partition | 1-7 | `--faults partition --workload mixed` |
| 7 | invoice | latency | 1-7 | `--faults latency --workload invoice` |
| 8 | invoice | loss | 1-7 | `--faults loss --workload invoice --net-loss 20` |
| 9 | keysend | ckb-partition | 1-6 | `--faults ckb-partition --ckb-targets all` |
| 10 | invoice | ckb-latency | 1-7 | `--faults ckb-latency --ckb-delay 5000` |
| 11 | mixed | clock | 1-7 | `--faults clock --clock-skew-max 300` |
| 12 | mixed | kill + term | 1-8 | `--faults kill,term --workload mixed` |
| 13 | mixed | 全部网络类 | 1-7 | `--faults partition,latency,loss,ckb-partition,ckb-latency` |
| 14 | invoice | disconnect（高频，间隔 2 秒） | 1-7 | `--faults disconnect --nemesis-interval 2 --workload invoice` |
| 15 | mixed | disconnect + 自动重连 | 1-7 | `--faults disconnect --disconnect-reconnect auto --workload mixed` |
| 16 | mixed | 全部 | 1-8 | `--faults kill,term,pause,partition,latency,loss,ckb-partition,ckb-latency,clock,disconnect` |

每个场景用 `scripts/repeat.sh N ...` 跑多轮，让故障落在不同阶段。

## 6. 规划中（未实现）

- 通道开关负载：压测时并发 shutdown / force close
- MPP 大额拆分支付
- 链上结算检查：force close 后核对链上余额
- 资源故障：CPU / 内存 / 磁盘写满
- 直连、网状、watchtower、CCH 拓扑

# Local SemanticIndex chaining measurement — 2026-11-24

## Protocol

Compared task 6 from `docs/project-inputs/metrics.md` in the same already-warm
REPL and SemanticIndex. The serial path used four `eval` requests: resolve the
method symbol, then call `info`, `ownerChain`, and `occurrencesOf`. The chained
path performed the same resolution and calls in one Scala expression.

After one untimed warm-up of each path, ran ten paired samples, alternating
which path ran first. A local Python harness sent requests directly to the
existing Unix-domain REPL bridge socket and timed each complete path. This
avoids `scala-cli` process startup per evaluation, but includes bridge/REPL
request and response synchronization. The shared bridge was not started,
stopped, or reconfigured.

## Results

| Pair | Serial (ms) | Chained (ms) |
|---:|---:|---:|
| 1 | 2527.06 | 631.60 |
| 2 | 2525.60 | 642.69 |
| 3 | 2527.86 | 637.90 |
| 4 | 2516.58 | 628.15 |
| 5 | 2525.65 | 634.11 |
| 6 | 2505.39 | 638.30 |
| 7 | 2519.05 | 633.78 |
| 8 | 2497.34 | 633.54 |
| 9 | 2496.39 | 636.37 |
| 10 | 2525.57 | 648.20 |

| Metric | Serial | Chained |
|---|---:|---:|
| REPL `eval` requests per task | 4 | 1 |
| Median elapsed time | 2522.31 ms | 635.24 ms |
| Nearest-rank p90 | 2527.06 ms | 642.69 ms |

Median paired serial/chained time ratio: **3.95×**. Chaining used **75% fewer
REPL eval requests** and reduced median measured latency by about **74.8%**.

## Correctness and interpretation

All ten pairs returned matching symbol, info display name, owner chain, and
occurrence count. The result was
`_empty_/InspectSymbol$package.inspectSymbol().`, info name `inspectSymbol`,
owner chain `List("_empty_/InspectSymbol$package.", "_empty_/InspectSymbol$package.inspectSymbol().")`,
and occurrence count `1`. Each path made the same three `SemanticIndex` method
calls; chaining reduced REPL request/response round trips. No MCP or JSON-RPC
calls were used.

This is a warm, local REPL measurement—not a cold-start comparison and not a
measurement of LLM reasoning time, tokens, or an end-to-end agent task. The
timing improvement primarily reflects avoiding three bridge/REPL evaluation
round trips; it does not show that the underlying SemanticIndex operations
themselves became faster.

An additional timed pair with its individual requests and REPL responses
captured is preserved in
[`repl-semantic-index-chain-2026-11-24-run-log.md`](repl-semantic-index-chain-2026-11-24-run-log.md).
It is not included in the ten-sample summary above.

A separate no-Python parity check, with serial results returned to the LLM
between Bash calls, is recorded in
[`repl-semantic-index-llm-visible-log-2026-11-24.md`](repl-semantic-index-llm-visible-log-2026-11-24.md).
It was not timed.

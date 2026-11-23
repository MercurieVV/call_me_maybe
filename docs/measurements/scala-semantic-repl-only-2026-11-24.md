# REPL-only ScalaSemantic usage measurement — 2026-11-24

## Protocol

Five separate subagents read the updated skill and each evaluated this
read-only Scala expression through the existing REPL bridge:

```scala
semanticIndex.symbols.valuesIterator
  .filter(_.displayName == "inspectSymbol")
  .map(i => (i.displayName, i.symbol, i.kind))
  .toVector
```

The shared bridge was already running and was not started, stopped, or mutated.
Each subagent used one local bridge `eval` command. No ScalaSemantic MCP tools,
ScalaSemantic CLI commands, or JSON-RPC calls were used. Timings include the
`scala-cli run` bridge-command overhead and query against a warm shared REPL;
they are not cold-start or query-only latency.

## Results

| Run | Subagent | Exit | Real (s) | Correct method found | ScalaSemantic MCP/JSON-RPC calls |
|---:|---|---:|---:|---|---:|
| 1 | `repl-only-check-1` | 0 | 1.10 | Yes | 0 |
| 2 | `repl-only-check-2` | 0 | 0.99 | Yes | 0 |
| 3 | `repl-only-check-3` | 0 | 1.01 | Yes | 0 |
| 4 | `repl-only-check-4` | 0 | 1.01 | Yes | 0 |
| 5 | `repl-only-check-5` | 0 | 0.98 | Yes | 0 |

All five ran Scala code in the REPL and found
`_empty_/InspectSymbol$package.inspectSymbol().` with kind `METHOD`. The
expression also returned a same-named `CLASS`, so callers should narrow by
symbol kind (and owner/path if needed) when a display name is ambiguous.

Elapsed-time summary: median **1.01 s**, nearest-rank p90 **1.10 s**, range
**0.98–1.10 s**.

## Interpretation and limitations

- The clarified instruction was followed in all five fresh subagent contexts:
  ScalaSemantic core was used as Scala code in the REPL, with zero MCP or
  JSON-RPC calls.
- The same already-running REPL was shared, so these runs do not test fresh
  REPL initialization, cold latency, or independent index state.
- There is no paired pre-change run. The measurement confirms the intended usage
  and result stability, but does not establish a performance improvement.

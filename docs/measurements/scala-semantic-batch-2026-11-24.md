# ScalaSemantic batch measurement — 2026-11-24

## Protocol

Five separate subagents each ran this command once, sequentially:

```sh
/usr/bin/time -p mill -i app.runMain inspectSymbol SemanticIndex \
  /Users/viktorskalinins/IdeaProjects/my/ScalaSemanticMCP
```

The Scala program sends one `batch` request containing `find_symbol` followed
by `members`, with the second call using the first call's symbol. Each run
therefore makes one batch request and two ScalaSemantic subcalls. The explicit
root is the separate ScalaSemanticMCP project, not this repository.

The subagents had fresh conversational contexts, but shared the same warm
workspace/build caches. `real` includes Mill, app, CLI, and batch startup and
execution; it is not MCP-only latency.

## Results

| Run | Subagent | Exit | Real (s) | Batch requests | Subcalls | Both results OK | Symbol | Declared members reported |
|---:|---|---:|---:|---:|---:|---|---|---:|
| 1 | `batch-measure-1` | 0 | 2.54 | 1 | 2 | yes | `com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex#` | 19 |
| 2 | `batch-measure-2` | 0 | 2.45 | 1 | 2 | yes | `com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex#` | 18 |
| 3 | `batch-measure-3` | 0 | 2.52 | 1 | 2 | yes | `com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex#` | 19 |
| 4 | `batch-measure-4` | 0 | 2.57 | 1 | 2 | yes | `com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex#` | 19 |
| 5 | `batch-measure-5` | 0 | 2.60 | 1 | 2 | yes | `com/github/mercurievv/scalasemantic/semanticdb/SemanticIndex#` | 19 |

Elapsed-time summary: median **2.54 s**, nearest-rank p90 **2.60 s**, range
**2.45–2.60 s**. This is a five-run warm-path sample, not a cold/warm
comparison.

## Interpretation and limitations

- The exact symbol and successful two-call chain were stable across all five
  runs.
- The reported member count varied once (18 vs. 19). The subagents did not
  retain normalized member lists, so this may be a count-reporting difference
  or a result inconsistency; the quality guardrail is not fully established.
- This measures a single batch workflow only. There is no serial-call timing
  baseline, token/cost data, cold-start sample, or source-edit/cache-refresh
  test in this run.
- Each app execution invokes one batch request with two underlying tool calls;
  batch reduces orchestration calls, not the server's subcall count.

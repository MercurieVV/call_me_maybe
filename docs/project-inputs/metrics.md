# Project metrics input

Measure the skill against fresh-session tasks, not just tool usage. Lower
latency, tokens, or call counts are improvements only when correctness and
project isolation stay at least as good as the baseline.

## Scorecard

| Goal | Primary metric | Quality guardrail |
|---|---|---|
| Discover API signatures/models quickly | Time from task start to a correct signature plus required input/output model; record MCP/tool round trips and tokens | Exact symbol, parameter types, optionality, result shape, and referenced types match MCP schema or source |
| Chain deterministic calls | LLM↔MCP round trips per task; percentage of dependent calls handled inside a custom Scala/REPL MCP chain | Track underlying MCP subcalls separately; branches, dependencies, and failures are handled correctly |
| Understand project structure | Correct-root, build-tool, module, and compile-command selection rate; time to identify them | No analysis against the wrong project or module |
| Work across projects | Cross-project contamination rate and successful tasks per distinct root | Target zero wrong-root results; report root switches and whether contexts were isolated |
| Handle code/build-cache changes | Stale-result rate after source edit and successful compile; time to observe the updated signature | Target zero stale results after the documented refresh/restart procedure |
| Reduce exploration cost without losing quality | Median and p90 wall-clock time, LLM tokens/cost, and source/tool operations per completed task | Correctness and completeness must not regress; report task failures and unsupported claims |

Count **LLM↔MCP round trips** separately from the MCP calls made by a custom
Scala/REPL chain. Report REPL/client startup and compilation separately from
time spent on the actual query. The five-run batch measurement is historical
evidence, not the target workflow or a substitute for a serial baseline.

## Benchmark tasks

Use the same tasks for a serial-call baseline and the custom Scala/REPL workflow:

1. Discover one MCP tool's input/output schema and the corresponding Scala
   request/result model and signatures.
2. Resolve a symbol and inspect its members, comparing serial calls with one
   custom Scala/REPL chain that invokes MCP tools and validates intermediate
   results.
3. Identify the correct build/module and analyze a symbol in each of two
   project roots, checking that the results do not leak across roots.
4. Change a method or model, compile, and verify that the updated signature is
   visible after the documented MCP freshness check or REPL restart.
5. From an unfamiliar API name, find its exact symbol, signature, and only the
   referenced model types needed to write a small valid Scala call.
6. With the same project root and warm index, resolve the `inspectSymbol`
   method from `semanticIndex.symbols`, then use its exact symbol to call
   `info`, `ownerChain`, and `occurrencesOf` in one Scala expression. Compare
   with four separate REPL evaluations: resolve the symbol, then call each
   method using the returned symbol.

For each task, record whether the answer is correct and complete before
comparing cost. Run both cold and warm sessions where startup matters; use
multiple repetitions and report median and p90 rather than a single best run.
MCP-dependent custom-chain comparisons are not executable until the REPL MCP
client exists; do not substitute the server's `batch` tool for that
implementation. Task 6 is a local SemanticIndex chain and can be run now.

For task 6, count REPL `eval` requests separately from `SemanticIndex` method
calls. The expected orchestration comparison is one chained evaluation versus
four serial evaluations, with the same symbol, `info`, owner chain, and
occurrence results. No MCP or JSON-RPC calls are involved.

The chained expression for this repository is:

```scala
semanticIndex.symbols.valuesIterator
  .find(i => i.displayName == "inspectSymbol" && i.kind.toString == "METHOD")
  .map(i =>
    (
      i.symbol,
      semanticIndex.info(i.symbol).map(_.displayName),
      semanticIndex.ownerChain(i.symbol),
      semanticIndex.occurrencesOf(i.symbol).size
    )
  )
```

The expected result contains symbol
`_empty_/InspectSymbol$package.inspectSymbol().`, info name `inspectSymbol`,
an owner chain ending in that symbol, and occurrence count `1`. For the serial
baseline, evaluate symbol resolution, `info`, `ownerChain`, and `occurrencesOf`
separately, passing the returned symbol to each later expression.

## Initial acceptance gates

- No cross-project contamination.
- No known stale signature after the prescribed compile/refresh/restart steps.
- Exact symbols and types are resolved rather than guessed.
- API/model answers remain correct and complete versus the serial baseline.
- Once the quality gates pass, improve median/p90 completion time and reduce
  unnecessary LLM↔MCP round trips and token use.

Do not set numeric speedup targets until a baseline is measured. Raw counts such
as schemas loaded, tools called, or files scanned are diagnostic metrics, not
success measures on their own.

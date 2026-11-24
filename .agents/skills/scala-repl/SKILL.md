---
name: scala-repl
description: Bootstrap project-aware Scala REPL workflows, inspect SemanticDB, discover ScalaSemantic MCP schemas/signatures, and build efficient tool chains.
---

# Scala REPL, SemanticDB, and tool chains

Use this skill when you need to run exploratory Scala expressions or inspect compiled Scala code interactively. Prefer a Scala REPL over repeated one-off shell evaluations when several related questions can be answered in one session.

## Session setup and workflow

1. Read project instructions; identify the target root, module, and build.
   Resolve this skill's absolute directory from its loaded path as `SKILL_DIR`.
   Run REPL commands from the target root and reference scripts through it.
2. Use direct core access for local compiled SemanticDB and MCP tools for
   server capabilities. The bridge cannot call MCP; make remote calls directly
   and use Scala for local computation. Do not substitute the server's `batch`
   tool for custom REPL logic.
3. Check SemanticDB freshness; compile only when sources changed or the index
   is missing/stale.
4. Align the MCP root and REPL `semanticProjectRoot` with the SemanticDB root
   containing the target module(s); compile every module whose SemanticDB you
   need. Confirm and restore the MCP root when switching. The bridge takes its
   root from its startup directory, so run one bridge per target root and pass
   explicit roots to CLI tools.

## ScalaSemantic API discovery

For an unfamiliar Scala API, use the active ScalaSemantic tool catalog and
resolve real signatures before writing code:

1. Treat the active catalog as authoritative for tool names and request
   arguments. Use `output_schema(tool = "...")` only when you need the response
   shape.
2. Resolve names with `find_symbol`, then pass the exact returned symbol to
   `method_signature(detailed = true)`. Inspect only needed types with
   `members` or `symbol_source`; verify lookup results before using them.
3. `method_signature` may omit source-level defaults. Inspect the definition
   when default values or other source details matter.

For example, to discover `SemanticIndex`'s API, first resolve the type:

```text
find_symbol(query = "SemanticIndex", exact = true, kind = "CLASS")
```

Then use the exact `symbol` returned by that call:

```text
method_signature(symbol = "<returned symbol>", detailed = true)
members(symbol = "<returned symbol>", include = ["declared"], detailed = true)
```

MCP tool schemas describe only those tools, not the Scala library. Use
`scalasemantic-core` only as Scala code in the REPL—never through handcrafted
JSON/JSON-RPC or the `scalasemantic-mcp` server/CLI. Loading this skill does not
execute scripts or automatically call tools.

## Direct ScalaSemantic core access

For local compiled SemanticDB, import `io.github.mercurievv::scalasemantic-core`
and use `SemanticIndex` from the REPL.

1. Compile the relevant module with SemanticDB enabled. This repository uses
   `mill app.compile`; for other projects, inspect the build:
   - Mill: compile the needed module with SemanticDB enabled.
   - sbt: compile the project with its SemanticDB plugin/settings.
   - Scala CLI: use `compile --semanticdb --sourceroot <project-root>` and add
     `--test` when test sources matter.
2. Resolve `SCALA_VERSION` from the target project's build and
   `CORE_VERSION` from the active ScalaSemantic server release/configuration.
   Convert a release such as `v0.9.1` to its Maven artifact version `0.9.1`.
   In this repository the values are Scala `3.8.4` and core `0.9.1`; do not
   reuse them for another project without checking its configuration. If the
   matching core release cannot be established, do not guess.
3. Start the REPL from the project root, replacing the placeholders:

   ```sh
   SKILL_DIR="/absolute/path/to/scala-repl"
   SCALA_VERSION="<target project's Scala version>"
   CORE_VERSION="<matching ScalaSemantic Maven version>"
   scala-cli repl --scala "$SCALA_VERSION" \
     --dep "io.github.mercurievv::scalasemantic-core:$CORE_VERSION" \
     --repl-init-script-file "$SKILL_DIR/repl-init.scala"
   ```

`repl-init.scala` sets `semanticProjectRoot` from the working directory and
loads `semanticIndex` lazily. Query it with ordinary Scala collections, for
example:

```scala
semanticIndex.symbols.valuesIterator
  .filter(_.displayName.contains("Inspect"))
  .map(info => (info.displayName, info.symbol, info.kind))
  .toVector

semanticIndex.occurrencesOf("app/SomeType#")
semanticIndex.info("app/SomeType#")
semanticIndex.ownerChain("app/SomeType#")
```

Resolve symbols from `semanticIndex.symbols` rather than guessing strings.
`SemanticIndex` exposes documents, symbols, and occurrences—not higher-level
MCP analysis tools.

Before each REPL eval, check whether the requested local results form a
deterministic dependency chain. If several `SemanticIndex` calls use the same
symbol, resolve it and compute all requested results in one Scala expression,
printing a small tuple instead of sending the symbol back to the LLM for
follow-up evals. For multiple symbols, resolve them in one collection
expression and map each to its needed results.

The bridge accepts normally formatted multi-line code and filters automatic
REPL echoes of expression results and top-level definitions. It returns
explicit program output (such as `println`) and diagnostics; bare expressions
produce no output, so print only the compact result the LLM needs. For example,
replace `targetMethod` with the requested name:

```scala
val result = semanticIndex.symbols.valuesIterator
  .find(i => i.displayName == "targetMethod" && i.kind.toString == "METHOD")
  .map(i =>
    (
      i.symbol,
      semanticIndex.info(i.symbol).map(_.displayName),
      semanticIndex.ownerChain(i.symbol),
      semanticIndex.occurrencesOf(i.symbol).size
    )
  )
println(result)
```

Use separate evals only when the next query depends on an LLM decision after
inspecting an earlier result, or when combining results would be unwieldy. This
is Scala composition inside the REPL, not the MCP `batch` tool.

## REPL automation

In this repository, use the project-local `./scala_repl_add` command to manage
the persistent REPL or send code to its bridge:

```sh
./scala_repl_add start
./scala_repl_add status
./scala_repl_add 'println(1 + 1)'

./scala_repl_add <<'SCALA'
val result = 1 + 1
println(result)
SCALA

./scala_repl_add stop
```

The current bridge is pinned to Scala `3.8.4` and core `0.9.1`, and expects
`repl-init.scala` under `.agents/skills/scala-repl` inside the target root.
Use it only when those versions and paths match. For another project, use the
version-resolved one-off REPL command above; making the bridge configurable is
future code work.

The bridge runs one REPL from its startup directory. `eval` executes Scala with
the current user's permissions over a private `.repl-session` socket; use only
in a trusted workspace and do not expose the socket.

Compile before querying SemanticDB; restart after recompiling to reload the lazy
index, and stop the bridge when done.

For one-off sessions, use the REPL command above; piped stdin may be unreliable
with JLine.

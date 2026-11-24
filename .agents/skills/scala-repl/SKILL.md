---
name: scala-repl
description: Use Scala REPL programs for deterministic multi-step automation across domains; includes project-aware SemanticDB and ScalaSemantic workflows.
---

# Programmatic automation with Scala REPL

Use this skill for deterministic automation in any domain—not only SemanticDB
or ScalaSemantic. **The Scala REPL is the default runtime for automation.**
When a task involves scripting, data processing, repeated commands, or
chaining predictable steps, put that logic in a REPL program/eval instead of
running it as Bash, Python, or another helper script. Keep intermediate
values in the REPL and print only the compact result the LLM needs; this
avoids separate tool round-trips and LLM turns for deterministic substeps.

Use Bash as thin transport to start/control the bridge or invoke it with
`scala_repl_add`; do not put automation logic in Bash pipelines or scripts
when the REPL can perform it. Likewise, do not reach for Python or another
script runtime for glue, transformations, or orchestration by default. Use
external commands only for operations that genuinely require OS-specific
behavior or a tool with no usable Scala interface, and keep the rest of the
workflow in Scala where practical.

This bridge runs Scala code and cannot call MCP tools directly. Call tools
through their supported agent interfaces; use the REPL for deterministic
computation over available local data or supported libraries, and do not
handcraft MCP/JSON-RPC requests as a workaround. The SemanticDB and
ScalaSemantic workflows below are domain-specific examples of the broader
automation pattern. Keep the LLM in the loop for judgment, user choices, or
decisions that genuinely depend on interpreting intermediate results.

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

## Example: ScalaSemantic API discovery

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

## Example: Direct ScalaSemantic core access

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

Use the global `scala_repl_add` command (on PATH) from the target project root
to manage the persistent REPL or send code to its bridge:

```sh
scala_repl_add start
scala_repl_add status
scala_repl_add 'println(1 + 1)'

scala_repl_add <<'SCALA'
val result = 1 + 1
println(result)
SCALA

scala_repl_add stop
```

Stateless mode (no bridge, fresh JVM per call, runs in the current directory).
`os-lib` and `scalasemantic-core` are preloaded: `semanticProjectRoot` and a
lazy `semanticIndex` (`SemanticIndex`) are in scope, as in `repl-init.scala`.
Compile the project with SemanticDB first. The hook allows `scala_repl_add` only as the whole bash
command, so for code containing a single quote (`'`) or shell characters outside
quotes, pass it through a quoted heredoc:

```sh
scala_repl_add run 'println(os.read(os.pwd / "README.md"))'

scala_repl_add run <<'SCALA'
println(os.list(os.pwd).filter(_.last.startsWith(".")).size)
SCALA
```

## Searching Scala code: use `semanticIndex`, not text scanning

When the task is to find where something is defined or used in Scala code
(usages, callers, config keys' consumers, call sites), query `semanticIndex`
first. Do not `os.walk` + regex over `.scala` files unless the repo has no
SemanticDB (check with `scala_repl_add help` / compile first).

```scala
println(usagesOf("OAuthTokenService|getOAuthToken", 40).mkString("\n"))   // file:line ROLE name
println(findSymbols("CatHeadersFlow").map(i => (i.symbol, i.kind)))
```

Run it with `scala_repl_add start` + `eval`: the index loads once (~15 s for
`~/cat`) and stays loaded. In `run` mode it reloads on every call. Paths are
relative to the repo's SemanticDB root. Text scanning is for non-Scala files
(yml, conf, json, md).

Run `scala_repl_add help` to list what is preloaded (`os`, `SemanticIndex`,
`semanticProjectRoot`, lazy `semanticIndex`, `findSymbols`, `usagesOf`, common imports) before writing code.

Defaults are Scala `3.8.4` and core `0.9.1`. `os-lib` is preloaded in both the
bridge REPL and `run` mode. For another project, set
`SCALA_REPL_SCALA_VERSION` and `SCALA_REPL_CORE_VERSION` before `start`. Set
`SCALA_REPL_ROOT` to use a root other than the current directory.

The bridge keeps REPL state across calls. Compile errors and exceptions do not
affect it. If the REPL dies (e.g. `System.exit`) or an eval exceeds the timeout
(60 s, `SCALA_REPL_EVAL_TIMEOUT_SECONDS` at `start`), the bridge restarts it and
reports that definitions were lost; re-send them.

The bridge runs one REPL from its startup directory. `eval` executes Scala with
the current user's permissions over a private `.repl-session` socket; use only
in a trusted workspace and do not expose the socket.

Write REPL code as multi-line quoted heredocs. Run `scala_repl_add help` (also
printed by `start`) for the preloaded helpers plus the live scala-semantic MCP
tool catalog and `SemanticIndex` API, generated at run time (cached; refreshed
when `scalasemantic-mcp` or `mcp-tools.sc` changes).

`scala_repl_add` rejects code that references Scala sources (a `".scala"` or
`"scala"` string, `.sbt`) or runs `os.proc` grep/rg/find, unless it uses
`semanticIndex`, `findSymbols` or `usagesOf`, or contains the comment
`// text-scan-ok` (for repos without SemanticDB or non-Scala files).

Compile before querying SemanticDB; restart after recompiling to reload the lazy
index, and stop the bridge when done.

For one-off sessions, use the REPL command above; piped stdin may be unreliable
with JLine.

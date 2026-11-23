---
name: scala-repl
description: Bootstrap project-aware Scala REPL workflows, inspect SemanticDB, discover ScalaSemantic MCP schemas/signatures, and build efficient tool chains.
---

# Scala REPL, SemanticDB, and tool chains

Use this skill when you need to run exploratory Scala expressions or inspect compiled Scala code interactively. Prefer a Scala REPL over repeated one-off shell evaluations when several related questions can be answered in one session.

## Session setup and workflow

1. Read the applicable project instructions and identify the correct project
   root and build entry point.
2. Check the active MCP tool catalog. Fetch the schema for only the tools
   needed for the task; do not dump every schema by default.
3. For an unfamiliar Scala API, use `find_symbol` followed by
   `method_signature(detailed = true)`, then inspect only necessary model types
   with `members` or `symbol_source`.
4. Check whether SemanticDB is present and fresh. Compile when sources changed
   or the index is missing/stale; do not rebuild blindly.
5. For deterministic MCP-only sequences, use one native `scala-semantic`
   `batch` call rather than serial MCP calls. Do not start Mill or Scala CLI
   just to wrap a single batch call; use Scala/REPL when it adds local
   computation. The current REPL bridge cannot invoke MCP directly.
6. Keep project contexts isolated. Confirm the active MCP root with
   `get_workspace_root` when available; restore the original root after
   switching with `set_workspace_root`. Pass an explicit root to CLI scripts
   when analyzing another project instead of relying on `.` defaults. Keep
   each REPL's `semanticProjectRoot` matched to its project.

**Current implementation boundary:** the persistent bridge loads
`scalasemantic-core` and queries SemanticDB directly; it does not invoke MCP
tools from Scala. Until a programmatic MCP client is available, use one
`scala-semantic` `batch` call instead of serial MCP calls when it can express
the dependent workflow.

## ScalaSemantic API discovery

When you encounter an unfamiliar Scala API, discover its real signatures and
types before writing calls. The REPL is for running Scala code; it does not
connect to MCP. Use the active ScalaSemantic MCP tools for discovery, then use
the REPL to try the resulting Scala code.

1. At the start of a task using this skill, check the active MCP tool catalog
   for the ScalaSemantic tool names and descriptions. The catalog is
   authoritative and may differ between environments.
2. Use the active tool catalog as the request-argument contract. If you need
   the response shape, retrieve that tool's output schema with
   `output_schema(tool = "...")`; omit `tool` only when you need every typed
   tool's output schema.
3. If you need a Scala method, resolve its name with `find_symbol`. Pass the
   exact returned symbol to `method_signature(detailed = true)`. Use `members`
   to inspect the owning type and `symbol_source` for docs or implementation.
4. Follow parameter and return types with additional `find_symbol`,
   `method_signature`, or `members` calls only as needed. Do not guess symbol
   strings or load the whole library by default.
5. In a `batch`, inspect every call's `ok` status. Later calls still run after
   an earlier call fails, so check that a search returned the expected
   candidate before depending on its `$ref`. If a search may be empty or
   ambiguous, resolve it first and batch only the calls that use its exact
   symbol.

For example, to discover `SemanticIndex`'s API, first resolve the type:

```text
find_symbol(query = "SemanticIndex", exact = true, kind = "CLASS")
```

Then use the exact `symbol` returned by that call:

```text
method_signature(symbol = "<returned symbol>", detailed = true)
members(symbol = "<returned symbol>", include = ["declared"], detailed = true)
```

MCP tool schemas describe the JSON interface; Scala method signatures describe
the library API. Do not treat one as a substitute for the other. The skill
instructions guide API discovery when relevant; loading the skill does not
execute scripts or automatically call tools.

## Direct ScalaSemantic core access

For SemanticDB inspection without MCP, use the `io.github.mercurievv::scalasemantic-core` library and `SemanticIndex`. Do not launch `scalasemantic-mcp`, call its CLI, or send JSON-RPC requests for this workflow.

1. Work from the Scala project root.
2. Compile the relevant module with SemanticDB enabled. In this repository, run `mill app.compile`; check `build.mill` for `-Xsemanticdb` and the project sourceroot. For other projects, inspect their build before choosing a compile command:
   - Mill: compile the relevant module(s), and ensure normal compilation emits SemanticDB.
   - sbt: compile the relevant project with its SemanticDB compiler plugin/settings enabled.
   - Scala CLI: compile with `--semanticdb` and the project sourceroot; include `--test` when test sources matter.
3. Use a core library version matching the ScalaSemantic release intended for the project. This repository pins `v0.9.1` in `.mcp.json`, so its Maven artifact version is `0.9.1`.
4. Start the REPL from the project root:

   ```sh
   scala-cli repl --scala 3.8.4 \
     --dep io.github.mercurievv::scalasemantic-core:0.9.1 \
     --repl-init-script-file .agents/skills/scala-repl/repl-init.scala
   ```

The init file resolves the REPL working directory as `semanticProjectRoot` and
defines `semanticIndex` as a lazy `SemanticIndex.fromProject` value. The library
is available at startup, but the project index is read only when first used.
Continue querying that value with ordinary Scala collections and methods, for
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

Resolve exact SemanticDB symbols from `semanticIndex.symbols`; do not guess symbol strings. `SemanticIndex` exposes the index data (documents, symbols, and occurrences), not the higher-level MCP analysis tools.

## REPL automation

For a persistent REPL across agent turns, use the Scala CLI/JVM PTY bridge:

```sh
scala-cli run .agents/skills/scala-repl/repl-bridge.scala -- start
scala-cli run .agents/skills/scala-repl/repl-bridge.scala -- eval '1 + 1'
scala-cli run .agents/skills/scala-repl/repl-bridge.scala -- status
scala-cli run .agents/skills/scala-repl/repl-bridge.scala -- stop
```

The bridge is a Scala CLI script that starts a detached JVM controller, which in
turn launches one Scala CLI REPL process with ScalaSemantic core available.
Scala CLI resolves the PTY and core dependencies automatically. The init script
sets `semanticProjectRoot` and defines `semanticIndex` as a lazy value, so the
library is ready to use without scanning SemanticDB until `semanticIndex` is
first referenced. The bridge accepts single-line Scala expressions over a
private Unix socket under `.repl-session/`. `eval` executes arbitrary Scala with
the current user's permissions; only use it in a trusted workspace and do not
expose the socket.

Compile the project with SemanticDB enabled before querying its index (in this
repository, `mill app.compile`). `semanticIndex` is cached after its first
access; restart the REPL after recompiling if you need it to read the new
SemanticDB. Stop the session after the task.

For a one-off REPL, use `--repl-init-script-file` directly. Piped stdin may not be processed reliably by the JLine REPL. If a persistent PTY bridge is unavailable, use a small Scala CLI script with the same core dependency instead of issuing many separate shell snippets.

## Build-tool and script use

- Use the repository's existing build tool and module layout; do not assume sbt or Mill targets from another project.
- For standalone Scala CLI scripts, declare their dependencies with Scala CLI directives or command-line options.
- Keep SemanticDB generation and analysis separate: the build emits SemanticDB, while `SemanticIndex` reads and queries it.

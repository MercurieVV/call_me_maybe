# Scala REPL skill goals input

These are the product goals and acceptance criteria used to shape the
project-local `scala-repl` skill. They are input data, not skill instructions.
The implemented workflow belongs in `.agents/skills/scala-repl/SKILL.md`.

## Goals

1. **Minimize ScalaSemantic API discovery time.** In a fresh session, identify
   available MCP tools and retrieve exact signatures and required model types
   with targeted lookups.
2. **Prefer deterministic chains in Scala.** Reduce intermediate
   LLM-to-MCP round trips by composing tool calls in a REPL/script with a
   programmatic MCP client. Do not use the server's limited `batch` tool as a
   substitute; until the client exists, make required remote calls directly
   and keep local deterministic work in Scala.
3. **Understand project structure.** Identify project root, build tool,
   modules, Scala version, SemanticDB setup, and relevant code before analysis.
4. **Work across projects safely.** Support explicit contexts for multiple
   roots without sharing indexes, build assumptions, or state accidentally.
5. **Remain correct across code and cache changes.** Compile changed code and
   ensure MCP or REPL indexes reflect the new SemanticDB before trusting results.
6. **Reduce exploration time and cost without losing quality.** Use targeted
   symbols, signatures, schemas, and source ranges; preserve or improve
   correctness and completeness.

## Acceptance criteria

- Exact method signatures and required types are resolved rather than guessed.
- Only schemas and model types needed for the task are loaded by default.
- Project root, build tool, module, and SemanticDB freshness are checked before
  relying on analysis results.
- Every operation is explicitly scoped to a project; cross-project
  contamination is treated as a failure.
- Deterministic dependent steps use a custom Scala/MCP chain when available,
  with branching, validation, and explicit failure handling. Do not rely on
  the server's `batch` tool; until the client exists, make required remote
  calls directly and preserve their result context.
- Efficiency improvements are accepted only when answer correctness and
  completeness do not regress.

## Current implementation boundary

The persistent Scala CLI bridge supports direct `scalasemantic-core` queries and
keeps a REPL alive. It does not yet invoke MCP tools programmatically. The
server's `batch` tool is not an interim workflow; flexible custom MCP
orchestration requires an MCP client inside the REPL.

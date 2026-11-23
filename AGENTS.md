# Agent instructions

## Project direction

This is an early-stage Scala 3 project exploring **programmatic tool calling**: move deterministic, multi-step work into a Scala script or persistent REPL chain and invoke the chain as a unit, rather than asking the model to call tools and make each intermediate decision one at a time. The target is to make several MCP calls through `scala-semantic` from Scala, reducing interaction time and token use while keeping deterministic logic in code.

Treat this as the project direction supplied by its owner, not as an architecture already present in the code. Keep repeatable logic and sequencing in Scala; use MCP calls for external capabilities. The current REPL bridge does not yet call MCP programmatically. Until that exists, prefer one `scala-semantic` `batch` call over serial tool calls; it can run up to 20 tools and pass earlier results to later calls with `$ref`. `app/src/InspectSymbol.scala` is the first example: resolve a symbol, then inspect its members in one batch. Use Mill for the project build and Scala CLI for standalone scripts.

Project goals and acceptance criteria are input data in
[`docs/project-inputs/skill-goals.md`](docs/project-inputs/skill-goals.md);
the finished operational workflow is `.agents/skills/scala-repl/SKILL.md`.

The effectiveness scorecard and benchmark protocol are input data in
[`docs/project-inputs/metrics.md`](docs/project-inputs/metrics.md).

The persistent Scala CLI REPL user story, target JVM controller, prior decisions, and tested cases are documented in [`docs/user-stories/persistent-scala-cli-repl.md`](docs/user-stories/persistent-scala-cli-repl.md).

The ScalaSemantic MCP schema and library-signature discovery flow is documented in [`docs/user-stories/scala-semantic-api-discovery.md`](docs/user-stories/scala-semantic-api-discovery.md) and `.agents/skills/scala-repl/SKILL.md`.

## Scala coding guidelines

Apply the shared guidance in the sibling checkout at [`../scala-coding-guidelines/AGENTS.md`](../scala-coding-guidelines/AGENTS.md); start with its [`wiki/index.md`](../scala-coding-guidelines/wiki/index.md) and relevant articles such as [Project Structure & Testing](../scala-coding-guidelines/wiki/scala-typelevel-fp/project-structure-and-testing.md) for deeper architecture decisions. In this project:

- Keep the core deterministic and typed; isolate MCP, process, and other side effects at the boundary. Prefer Scala CLI scripts for one-off deterministic workflows instead of introducing a separate shell or Python implementation.
- Model failure explicitly. Avoid `null`, `throw`, `return`, mutable `var`, unsafe casts, and unsafe effect execution.
- Keep dependencies pointing inward and inject external capabilities explicitly. Do not add generic effect abstractions or layers unless a concrete use case needs them.
- Use [the `scala-repl` skill](.agents/skills/scala-repl/SKILL.md) for direct REPL-based ScalaSemantic core inspection without MCP.

## Build, test, and lint

Build the Scala module with `mill app.compile`. Run the example chain with `scala-cli run app/src/InspectSymbol.scala -- <type-name> [project-root]`. There are no test or lint targets yet; document their actual Mill commands, including a single-test invocation, when they are added.

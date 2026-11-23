# Persistent Scala CLI REPL

## User story

As a developer or coding agent working in this project, I want to evaluate Scala
expressions in a persistent Scala CLI REPL, so that related explorations can
reuse definitions and loaded project data across tool calls without repeatedly
starting the Scala compiler or moving Scala work into another language.

The intended user prerequisite is Scala CLI (and its normal JVM requirements).
The session controller is a Scala CLI script. Users should not need to install
Python, tmux, or another unrelated runtime just to use the REPL.

## Acceptance criteria

- Start, evaluate, check, and stop a REPL session through a simple project
  interface.
- Keep one Scala CLI REPL process alive across separate requests; initialize it
  once so Scala definitions and loaded project data remain available.
- Make ScalaSemantic core available in the REPL, but defer reading the project's
  SemanticDB until an expression first accesses the index.
- Run submitted Scala in Scala, including direct ScalaSemantic core queries.
- Return expression output and compiler/runtime errors without losing session
  synchronization; output that happens to contain `scala> ` must not be mistaken
  for the REPL prompt.
- Keep the session local to the user and restrict access to its communication
  endpoint.
- Require Scala CLI and its normal JVM setup from the user's perspective; resolve
  the PTY implementation dependency automatically through Scala CLI.
- Initially support one complete, single-line expression per evaluation. Add
  multiline input only for a demonstrated use case.

## Decisions and constraints so far

- Keep deterministic analysis and sequencing in Scala. For repeatable
  non-interactive work, use Scala CLI scripts; for interactive exploration,
  reuse a REPL.
- Use Scala CLI as the REPL implementation. A REPL alone does not provide the
  cross-call session transport needed by this tool environment.
- Implement the session controller as a Scala CLI script. It runs as a JVM
  process, launches the Scala CLI REPL as a child process, and exposes
  start/eval/status/stop over a private Unix socket.
- Keep the bridge implementation and usage instructions together in the
  project-local `scala-repl` skill.
- Load ScalaSemantic core at startup, but make `semanticIndex` lazy so startup
  does not parse SemanticDB unless a query needs it.
- Use Pty4J for PTY allocation and the JDK's Unix-domain socket support for
  local communication. Scala CLI resolves the PTY dependency; Python is not
  required.
- Keep the REPL as a Scala CLI child process rather than embedding a compiler or
  REPL implementation into the controller.
- A PTY is needed for reliable interaction with the JLine-based REPL. Piped
  stdin was unreliable in this environment.
- Keep direct ScalaSemantic core inspection separate from MCP calls:
  `SemanticIndex.fromProject(semanticProjectRoot)` reads compiled SemanticDB
  from the REPL's explicitly resolved working directory.
  When MCP capabilities are needed, prefer ScalaSemantic's `batch` tool for
  deterministic chains; its v0.9.1 implementation supports up to 20 chained
  calls with `$ref`.
- The endpoint should remain local and permission-restricted. Evaluated Scala
  has the current user's permissions, so only use it in a trusted workspace.

## Cases already exercised

- Loaded ScalaSemantic core in a Scala 3 REPL and inspected the project index
  directly: 1 document, 25 symbols, and 135 occurrences.
- Used a ScalaSemantic `batch` chain to resolve a symbol and inspect its members
  on a temporary fixture; the fixture was removed afterward.
- Kept a definition across separate bridge requests (`val answer = 40`, then
  `answer + 2` produced `42`).
- Verified that printing text containing `scala> ` does not confuse prompt
  detection.
- Verified that a Scala compile error is returned and a later expression still
  works in the same REPL.
- Verified the Scala CLI bridge's start/status/stop lifecycle, direct
  ScalaSemantic initialization, and private Unix socket permissions.
- Verified that ScalaSemantic core is available at startup and the index loads
  when first referenced.
- In this environment, tmux was unavailable and the installed Screen version
  did not provide usable output capture.

## Out of scope for the first version

- Replacing Scala CLI or implementing a new Scala evaluator.
- Replacing ScalaSemantic MCP tools; the REPL provides direct core-library
  access, while MCP remains the route to higher-level server capabilities.
- Remote or shared REPL sessions.
- A general-purpose process supervisor or a multi-user session service.

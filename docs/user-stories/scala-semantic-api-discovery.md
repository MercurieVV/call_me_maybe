# ScalaSemantic API discovery

## User story

As an LLM using the Scala REPL skill, I want to discover the current
ScalaSemantic MCP tool contracts and the Scala library signatures and model
types needed for a task, so that I can write correct calls and Scala code
without loading the entire API into context.

## Acceptance criteria

- On skill use, the agent can identify the currently available ScalaSemantic
  MCP tools from the active tool catalog; it does not rely on a stale
  hand-maintained inventory.
- For a selected MCP tool, the agent can read its request arguments from the
  active tool definition and retrieve its output schema; together these define
  the JSON wire contract.
- For Scala library APIs, the agent can resolve exact symbols and retrieve
  method signatures, parameter details, members, and relevant referenced model
  types on demand.
- API discovery is read-only: it may call metadata and symbol/signature lookup
  tools, but does not compile or execute project code.
- Metadata retrieval is scoped to the tools, methods, and model types required
  for the current task; all schemas and library sources are not loaded by
  default.
- The Scala REPL remains a Scala execution environment. MCP discovery uses the
  available MCP tools directly until an MCP client is available in the REPL;
  the target workflow allows custom Scala code to orchestrate those calls.

## Decisions and constraints

- MCP schemas and Scala library signatures are different surfaces:
  - Active MCP tool definitions and `output_schema` responses define the JSON
    wire contract for server calls.
  - Scala signatures, docs, and members are authoritative for code using the
    Scala library.
- For MCP request arguments, use the active tool definition. Use
  `output_schema` for the relevant response shape; request every typed tool's
  output schema only when a broad inventory is explicitly useful.
- For library signatures, use `find_symbol` followed by `method_signature`;
  use `members` to inspect a type and `symbol_source` for definition details.
  Resolve referenced model types only when needed.
- The current bridge does not launch an MCP client or call MCP from Scala code.
  Programmatic MCP calls and chains from the REPL are a project goal, but require
  an MCP client and explicit transport/lifecycle design. Do not use the
  server's limited `batch` tool as an interim chain; until client integration is
  available, make required MCP calls directly and keep local computation in
  Scala.
- Loading the skill does not execute its Scala scripts. The agent follows the
  discovery instructions when the skill is relevant; it inspects the active
  tool catalog and fetches exact schemas or signatures as needed.

## Example discovery flow

1. Inspect the active MCP tool definitions for request arguments and retrieve
   the response schema for a relevant tool, for example
   `output_schema(tool = "find_symbol")`.
2. Resolve a Scala type, for example `find_symbol(query = "SemanticIndex",
   exact = true, kind = "CLASS")`.
3. Pass the returned exact symbol to `method_signature(detailed = true)` and
   `members`; query referenced model types only if the task needs them.
4. Use the REPL to execute or validate Scala code after the required API
   information is known.

## Out of scope for the first version

- A generated client for every MCP tool.
- A full-library API dump or an always-on API catalog cache.

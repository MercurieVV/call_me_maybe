# Scala REPL skill iteration measurement — 2026-11-24

## Protocol

Five separate subagents read the updated `SKILL.md` and completed the same
read-only discovery task against this project. They did not use the ScalaSemantic
`batch` tool or CLI wrappers. Each checked the project configuration, confirmed
the MCP root, called `find_symbol` followed by `method_signature`, and compared
the signature with the source.

## Results

All five runs reported:

- Root: `/Users/viktorskalinins/IdeaProjects/my/call_me_maybe`
- Scala version: `3.8.4`
- ScalaSemantic core Maven version: `0.9.1`
- Symbol: `_empty_/InspectSymbol$package.inspectSymbol().`
- MCP signature: `def inspectSymbol(query: String, root: String): Unit`
- Correctly identified the target root, versions, symbol, parameter types, and
  return type.
- Three direct MCP calls/roundtrips (`get_workspace_root`, `find_symbol`,
  `method_signature`), four local file reads, and no batch calls.

| Run | Subagent | Exit/result | Direct MCP calls | Local reads | Batch used | Source default noticed |
|---:|---|---|---:|---:|---|---|
| 1 | `skill-check-1` | Correct | 3 | 4 | No | Yes |
| 2 | `skill-check-2` | Correct | 3 | 4 | No | Yes |
| 3 | `skill-check-3` | Correct | 3 | 4 | No | Yes |
| 4 | `skill-check-4` | Correct | 3 | 4 | No | Yes |
| 5 | `skill-check-5` | Correct | 3 | 4 | No | Yes |

The source declares `root: String = "."`, but `method_signature` omits that
default. All five runs found this by checking the source, so source inspection
is necessary when default-argument behavior matters.

## Size comparison

Compared with the skill before checklist consolidation (`69f8379`):

| Version | Lines | Words | Bytes |
|---|---:|---:|---:|
| Before | 156 | 1,192 | 8,527 |
| After | 157 | 1,171 | 8,527 |

The updated skill uses 21 fewer whitespace-delimited words (about 1.8%); bytes
are unchanged. This is only a rough context-size proxy, not an actual token
measurement.

## Interpretation and limitations

- The five runs show consistent task correctness using direct MCP calls and
  confirm the new version-resolution instructions on this repository.
- This project has the same versions as the old hard-coded example, so these
  runs do not prove improved cross-project version selection.
- No wall-clock, token/cost, or matched pre-change skill baseline was measured.
  The earlier batch-wrapper timing is not comparable because that workflow is
  now excluded.

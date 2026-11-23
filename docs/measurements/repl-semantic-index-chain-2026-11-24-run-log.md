# Captured REPL chain run log — 2026-11-24

This is the captured request/response transcript from one newly timed pair.
It is not one of the ten samples summarized in
`repl-semantic-index-chain-2026-11-24.md`; that run retained timings and
correctness checks, but not individual REPL responses.

- Started: `2026-11-24T01:46:48.835+02:00`
- Ended: `2026-11-24T01:46:52.004+02:00`
- Context: existing warm REPL bridge, serial path followed by chained path
- Correctness: output parity passed for symbol, info name, owner chain, and
  occurrence count

```text
SERIAL (4 eval requests)
[1] request (631.17 ms): semanticIndex.symbols.valuesIterator.find(i => i.displayName == "inspectSymbol" && i.kind.toString == "METHOD").map(_.symbol)
    response:
      val res126: Option[String] = Some(
        "_empty_/InspectSymbol$package.inspectSymbol()."
      )
[2] request (629.88 ms): semanticIndex.info("_empty_/InspectSymbol$package.inspectSymbol().").map(_.displayName)
    response:
      val res127: Option[String] = Some("inspectSymbol")
[3] request (633.92 ms): semanticIndex.ownerChain("_empty_/InspectSymbol$package.inspectSymbol().")
    response:
      val res128: List[String] = List(
        "_empty_/InspectSymbol$package.",
        "_empty_/InspectSymbol$package.inspectSymbol()."
      )
[4] request (627.76 ms): semanticIndex.occurrencesOf("_empty_/InspectSymbol$package.inspectSymbol().").size
    response:
      val res129: Int = 1
Serial elapsed: 2523.07 ms

CHAINED (1 eval request)
[1] request (645.74 ms): semanticIndex.symbols.valuesIterator.find(i => i.displayName == "inspectSymbol" && i.kind.toString == "METHOD").map(i => (i.symbol, semanticIndex.info(i.symbol).map(_.displayName), semanticIndex.ownerChain(i.symbol), semanticIndex.occurrencesOf(i.symbol).size))
    response:
      val res130: Option[(String, Option[String], List[String], Int)] = Some(
        (
          "_empty_/InspectSymbol$package.inspectSymbol().",
          Some("inspectSymbol"),
          List(
            "_empty_/InspectSymbol$package.",
            "_empty_/InspectSymbol$package.inspectSymbol()."
          ),
          1
        )
      )
Chained elapsed: 645.79 ms
Output parity: PASS (symbol, info name, owner chain, occurrence count)
```

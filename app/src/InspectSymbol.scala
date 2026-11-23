//> using scala 3.8.4
//> using dep com.lihaoyi::upickle:4.4.3

import scala.sys.process.*

@main def inspectSymbol(query: String, root: String = "."): Unit =
  val request = ujson.Obj(
    "calls" -> ujson.Arr(
      ujson.Obj(
        "tool" -> "find_symbol",
        "args" -> ujson.Obj("query" -> query, "exact" -> true, "kind" -> "CLASS", "limit" -> 1)
      ),
      ujson.Obj(
        "tool" -> "members",
        "args" -> ujson.Obj("symbol" -> ujson.Obj("$ref" -> "0.symbols.0.symbol"))
      )
    )
  )

  val stdout = new StringBuilder
  val stderr = new StringBuilder
  val command = Seq("scalasemantic-mcp", "call", "batch", request.render(), root)
  val status =
    try
      Process(command, None, "SCALASEMANTIC_VERSION" -> "v0.9.1").!(
        ProcessLogger(
          line => stdout.append(line).append('\n'),
          line => stderr.append(line).append('\n')
        )
      )
    catch
      case error: java.io.IOException =>
        Console.err.println(s"Could not start scalasemantic-mcp: ${error.getMessage}")
        127

  Console.out.print(stdout.toString)
  Console.err.print(stderr.toString)
  if status != 0 then sys.exit(status)

  val batchFailed =
    try
      ujson.read(stdout.toString) match
        case response: ujson.Obj =>
          response.obj.get("results") match
            case Some(results: ujson.Arr) =>
              results.value.exists {
                case result: ujson.Obj =>
                  result.obj.get("ok") match
                    case Some(ujson.Bool(ok)) => !ok
                    case _                    => true
                case _ => true
              }
            case _ =>
              Console.err.println("ScalaSemantic batch response has no results array")
              true
        case _ =>
          Console.err.println("ScalaSemantic batch response is not a JSON object")
          true
    catch
      case error: ujson.ParseException =>
        Console.err.println(s"Could not parse ScalaSemantic batch response: ${error.getMessage}")
        true

  if batchFailed then sys.exit(1)

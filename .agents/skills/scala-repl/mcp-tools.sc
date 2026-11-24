//> using scala 3.8.4
//> using dep com.lihaoyi::os-lib:0.11.5
//> using dep com.lihaoyi::ujson:4.0.2
//> using dep io.github.mercurievv::scalasemantic-core:0.9.1

// Prints the live scala-semantic MCP tool catalog and the SemanticIndex API: `mcp-tools.sc <project-root>`.
import java.io.{BufferedReader, InputStreamReader}

val root = args(0)
val bin = os.Path(os.proc("which", "scalasemantic-mcp").call().out.text().trim)
val cache = os.home / ".cache" / "scala-repl" / "mcp-tools.txt"
val stamp = s"$bin ${os.mtime(bin)} ${os.mtime(os.Path(scriptPath))}"

def fetch(): String =
  val proc = os.proc(bin, "serve", root).spawn(env = Map("SCALASEMANTIC_SKIP_ROOT_CHECK" -> "1"), stderr = os.Pipe)
  val in = BufferedReader(InputStreamReader(proc.stdout.wrapped))
  def send(m: ujson.Value) = { proc.stdin.writeLine(ujson.write(m)); proc.stdin.flush() }
  def request(id: Int, method: String, params: ujson.Value = ujson.Obj()) =
    send(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params))
    Iterator.continually(in.readLine()).takeWhile(_ != null).map(l => scala.util.Try(ujson.read(l)).toOption)
      .collectFirst { case Some(j) if j.obj.get("id").exists(_.numOpt.contains(id.toDouble)) => j }
      .getOrElse(sys.error(s"no response to $method"))
  try
    request(1, "initialize", ujson.Obj("protocolVersion" -> "2024-11-05", "capabilities" -> ujson.Obj(),
      "clientInfo" -> ujson.Obj("name" -> "scala-repl", "version" -> "1")))
    send(ujson.Obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized"))
    val tools = request(2, "tools/list")("result")("tools").arr
    tools.map { t =>
      val props = t("inputSchema").obj.get("properties").map(_.obj).getOrElse(Map.empty)
      val required = t("inputSchema").obj.get("required").map(_.arr.map(_.str).toSet).getOrElse(Set.empty[String])
      val params = props.toSeq.map { case (n, s) =>
        val tpe = s.obj.get("type").map { case ujson.Str(x) => x; case other => ujson.write(other) }.getOrElse("any")
        s"$n${if required(n) then "" else "?"}: $tpe"
      }.mkString(", ")
      val doc = t.obj.get("description").map(_.str.linesIterator.next().take(80)).getOrElse("")
      s"  ${t("name").str}($params) - $doc"
    }.mkString("\n")
  finally proc.destroy()

def indexApi(): String =
  val c = classOf[com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex]
  def short(t: java.lang.reflect.Type) = t.getTypeName.replaceAll("[\\w$]+\\.", "").replace("$", ".")
  c.getMethods.filter(m => m.getDeclaringClass == c && !m.getName.contains("$")).map { m =>
    s"  ${m.getName}(${m.getGenericParameterTypes.map(short).mkString(", ")}): ${short(m.getGenericReturnType)}"
  }.distinct.sorted.mkString("\n")

val text =
  if os.exists(cache) && os.read.lines(cache).headOption.contains(stamp) then os.read.lines(cache).drop(1).mkString("\n")
  else
    val fresh = s"MCP tools (call via the agent's MCP interface, not from the REPL):\n${fetch()}\n\nSemanticIndex (use in the REPL; from reflection):\n${indexApi()}"
    os.write.over(cache, s"$stamp\n$fresh\n", createFolders = true)
    fresh
println(text)

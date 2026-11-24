import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}
import io.circe.Json
import io.circe.generic.auto.*

val semanticProjectRoot = java.nio.file.Paths.get(".").toAbsolutePath.normalize().toString
lazy val semanticIndex = SemanticIndex.fromProject(semanticProjectRoot)

def findSymbols(regex: String): Vector[scala.meta.internal.semanticdb.SymbolInformation] =
  val rx = regex.r
  semanticIndex.symbols.valuesIterator.filter(i => rx.findFirstIn(i.displayName).isDefined).toVector

lazy val repoDirs = os.list(os.Path(semanticProjectRoot)).filter(os.isDir).map(_.last)
def withRepo(uri: String): String =
  repoDirs.find(r => os.exists(os.Path(semanticProjectRoot) / r / os.RelPath(uri))).fold(uri)(r => s"$r/$uri")

def usagesOf(regex: String, limit: Int = 50, worktrees: Boolean = false): Vector[String] =
  findSymbols(regex).flatMap { i =>
    semanticIndex.occurrencesOf(i.symbol).map((uri, o) => (withRepo(uri), o, i))
  }.filter((p, _, _) => worktrees || !p.startsWith("worktrees/"))
    .map((p, o, i) => s"$p:${o.range.fold(0)(_.startLine + 1)} ${o.role} ${i.displayName}")
    .take(limit)

case class Doc(path: String, lines: Vector[String])
// every text/config file under the root (git worktrees excluded), read once; filter with plain Scala
lazy val corpus: Vector[Doc] =
  val skipDirs = Set(".git", "target", "node_modules", ".bsp", ".idea", ".metals", ".bloop", ".repl-session", "out")
  val textExts = Set("scala", "sc", "sbt", "java", "conf", "yml", "yaml", "json", "properties", "md", "xml", "sh", "txt", "tf", "sql", "toml")
  val root = os.Path(semanticProjectRoot)
  os.walk.stream(root, skip = p => skipDirs(p.last) || (p.last == "worktrees" && p / os.up == root)).toArray.iterator
    .filter(f => os.isFile(f) && textExts(f.ext) && os.size(f) < 2000000)
    .map(f => Doc(f.relativeTo(root).toString, Try(os.read.lines(f).toVector).getOrElse(Vector.empty)))
    .toVector

val definitionsByPath = scala.collection.mutable.Map.empty[String, Vector[(Int, String)]]
// nearest definition at or above a 1-based line of an indexed Scala file, as Owner.name; feed the name to usagesOf
def enclosing(path: String, line: Int): Option[String] =
  val defs = definitionsByPath.getOrElseUpdate(path, {
    val rest = path.substring(path.indexOf('/') + 1)
    semanticIndex.document(rest).orElse(semanticIndex.document(path)).toVector.flatMap(_.occurrences)
      .filter(o => o.role.toString == "DEFINITION" && (o.symbol.endsWith(".") || o.symbol.endsWith("#")) && !semanticIndex.isLocal(o.symbol) && o.range.isDefined)
      .map(o => (o.range.get.startLine + 1, o.symbol)).sortBy(_._1)
  })
  defs.takeWhile(_._1 <= line).lastOption.map { (_, s) =>
    val owner = semanticIndex.displayName(semanticIndex.owner(s))
    if owner.isEmpty then semanticIndex.displayName(s) else s"$owner.${semanticIndex.displayName(s)}"
  }

def searchText(regex: String, limit: Int = 60, pathPrefix: String = "", exts: Set[String] = Set.empty): Vector[String] =
  val rx = regex.r
  corpus.iterator
    .filter(d => d.path.startsWith(pathPrefix) && (exts.isEmpty || exts(d.path.split('.').last)))
    .flatMap(d => d.lines.iterator.zipWithIndex.collect { case (l, i) if rx.findFirstIn(l).isDefined =>
      val in = if d.path.endsWith(".scala") then enclosing(d.path, i + 1).fold("")(e => s" [in $e]") else ""
      s"${d.path}:${i + 1}:$in ${l.trim.take(160)}" })
    .take(limit).toVector

// numbered lines of the file whose path ends with pathEnd
def show(pathEnd: String, from: Int = 1, to: Int = Int.MaxValue): Unit =
  corpus.filter(_.path.endsWith(pathEnd)).foreach { d =>
    println(s"## ${d.path}")
    d.lines.zipWithIndex.slice(from - 1, to).foreach((l, i) => println(s"${i + 1}: $l"))
  }

// matches with surrounding lines, grouped per file
def ctx(regex: String, pathPrefix: String = "", before: Int = 2, after: Int = 3, limit: Int = 20, exts: Set[String] = Set.empty): Unit =
  val rx = regex.r
  corpus.iterator
    .filter(d => d.path.startsWith(pathPrefix) && (exts.isEmpty || exts(d.path.split('.').last)))
    .flatMap(d => d.lines.zipWithIndex.collect { case (l, i) if rx.findFirstIn(l).isDefined => (d, i) })
    .take(limit)
    .foreach { (d, i) =>
      println(s"## ${d.path}:${i + 1}")
      (math.max(0, i - before) to math.min(d.lines.size - 1, i + after)).foreach(j => println(s"${j + 1}: ${d.lines(j)}"))
    }
// run an external command (git, gh, aws, az, psql, sbt) and return exit code + output; no temp files or sleeps
val shAlternatives: Map[String, String] = Map(
  "grep" -> "corpus/searchText/ctx/usagesOf", "rg" -> "corpus/searchText/ctx/usagesOf", "find" -> "corpus or os.walk",
  "cat" -> "os.read / show", "head" -> "os.read.lines(...).take", "tail" -> "os.read.lines(...).takeRight", "ls" -> "os.list",
  "sed" -> "Scala String/collection ops", "awk" -> "Scala collections", "jq" -> "decodeAs[T](...) / parseJson(...)", "python" -> "Scala + circe", "python3" -> "Scala + circe",
  "curl" -> "requests", "sleep" -> "buildResult(waitSec)", "bash" -> "Scala", "sh" -> "Scala", "echo" -> "println", "wc" -> "Scala collections")
def sh(cmd: String*): String = shT(55, cmd*)
def shT(timeoutSec: Int, cmd: String*): String =
  shAlternatives.get(cmd.headOption.fold("")(c => c.split('/').last)) match
    case Some(alt) => s"refused: ${cmd.head} has a Scala alternative: $alt"
    case None => shRun(timeoutSec, cmd*)
def shRun(timeoutSec: Int, cmd: String*): String =
  Try(os.proc(cmd).call(cwd = os.Path(semanticProjectRoot), check = false, stderr = os.Pipe, timeout = timeoutSec * 1000L)) match
    case scala.util.Success(r) => s"exit=${r.exitCode}\n${r.out.text()}"
    case scala.util.Failure(e) => s"failed: ${e.getClass.getSimpleName} (timeout=${timeoutSec}s)"

// typed JSON: decodeAs[List[(version: String, success: Boolean)]](text); case classes and named tuples both work; use parseJson(text).hcursor for untyped access
def decodeAs[T: io.circe.Decoder](s: String): T = io.circe.parser.decode[T](s).fold(throw _, identity)
def parseJson(s: String): Json = io.circe.parser.parse(s).fold(throw _, identity)

// file content at another revision, and diffs between revisions
def gitShow(rev: String, path: String): String = sh("git", "--no-pager", "show", s"$rev:$path")
def gitDiff(a: String, b: String, path: String = ""): String =
  sh(("git" +: "--no-pager" +: "diff" +: a +: b +: (if path.isEmpty then Nil else Seq("--", path)))*)

// long build in the background (eval limit is 60 s): build("sbt","-batch","Test/compile") then buildResult() until it returns a result
var buildJob: Option[scala.concurrent.Future[String]] = None
def build(cmd: String*): Unit =
  buildJob = Some(scala.concurrent.Future {
    val r = os.proc(cmd).call(cwd = os.Path(semanticProjectRoot), check = false, stderr = os.Pipe, timeout = 3600000L)
    (s"exit=${r.exitCode}" +: r.out.lines().filter(l => l.contains("[error]") || l.contains("[success]"))).take(60).mkString("\n")
  }(scala.concurrent.ExecutionContext.global))
def buildResult(waitSec: Int = 50): String =
  buildJob.fold("no build started")(j => Try(scala.concurrent.Await.result(j, scala.concurrent.duration.Duration(waitSec, "s"))).getOrElse("still running"))

println(s"SemanticDB project root=$semanticProjectRoot")
println("SCALA_REPL_READY")

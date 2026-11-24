import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

val semanticProjectRoot = java.nio.file.Paths.get(".").toAbsolutePath.normalize().toString
lazy val semanticIndex = SemanticIndex.fromProject(semanticProjectRoot)

def findSymbols(regex: String) =
  val rx = regex.r
  semanticIndex.symbols.valuesIterator.filter(i => rx.findFirstIn(i.displayName).isDefined).toVector

lazy val repoDirs = os.list(os.Path(semanticProjectRoot)).filter(os.isDir).map(_.last)
def withRepo(uri: String): String =
  repoDirs.find(r => os.exists(os.Path(semanticProjectRoot) / r / os.RelPath(uri))).fold(uri)(r => s"$r/$uri")

def usagesOf(regex: String, limit: Int = 50, worktrees: Boolean = false) =
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
println(s"SemanticDB project root=$semanticProjectRoot")
println("SCALA_REPL_READY")

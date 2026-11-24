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

def usagesOf(regex: String, limit: Int = 50) =
  findSymbols(regex).flatMap { i =>
    semanticIndex.occurrencesOf(i.symbol).map((uri, o) => s"${withRepo(uri)}:${o.range.fold(0)(_.startLine + 1)} ${o.role} ${i.displayName}")
  }.take(limit)

case class Doc(path: String, lines: Vector[String])
// every text/config file under the root, read once; filter with plain Scala
lazy val corpus: Vector[Doc] =
  val skipDirs = Set(".git", "target", "node_modules", ".bsp", ".idea", ".metals", ".bloop", ".repl-session", "out")
  val textExts = Set("scala", "sc", "sbt", "java", "conf", "yml", "yaml", "json", "properties", "md", "xml", "sh", "txt", "tf", "sql", "toml")
  val root = os.Path(semanticProjectRoot)
  os.walk.stream(root, skip = p => skipDirs(p.last)).toArray.iterator
    .filter(f => os.isFile(f) && textExts(f.ext) && os.size(f) < 2000000)
    .map(f => Doc(f.relativeTo(root).toString, Try(os.read.lines(f).toVector).getOrElse(Vector.empty)))
    .toVector

def searchText(regex: String, limit: Int = 60, pathPrefix: String = "", exts: Set[String] = Set.empty): Vector[String] =
  val rx = regex.r
  corpus.iterator
    .filter(d => d.path.startsWith(pathPrefix) && (exts.isEmpty || exts(d.path.split('.').last)))
    .flatMap(d => d.lines.iterator.zipWithIndex.collect { case (l, i) if rx.findFirstIn(l).isDefined => s"${d.path}:${i + 1}: ${l.trim.take(160)}" })
    .take(limit).toVector
println(s"SemanticDB project root=$semanticProjectRoot")
println("SCALA_REPL_READY")

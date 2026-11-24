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

def searchText(regex: String, limit: Int = 60, exts: Set[String] = Set.empty): Vector[String] =
  val rx = regex.r
  val skipDirs = Set(".git", "target", "node_modules", ".bsp", ".idea", ".metals", ".bloop", ".repl-session", "out")
  val textExts = Set("scala", "sc", "sbt", "java", "conf", "yml", "yaml", "json", "properties", "md", "xml", "sh", "txt", "tf", "sql", "toml")
  val root = os.Path(semanticProjectRoot)
  os.walk.stream(root, skip = p => skipDirs(p.last)).toArray.iterator
    .filter(f => os.isFile(f) && textExts(f.ext) && (exts.isEmpty || exts(f.ext)) && os.size(f) < 2000000)
    .flatMap(f => Try(os.read.lines(f)).getOrElse(Vector.empty).iterator.zipWithIndex
      .collect { case (l, i) if rx.findFirstIn(l).isDefined => s"${f.relativeTo(root)}:${i + 1}: ${l.trim.take(160)}" })
    .take(limit).toVector

def cmdUsages(regex: String, limit: Int): String =
  val r = usagesOf(regex, limit)
  if r.isEmpty then s"no occurrences of /$regex/ in the index (name wrong, or repo not indexed: scala_repl_add index)" else r.mkString("\n")

def cmdSymbols(regex: String, limit: Int): String =
  val r = findSymbols(regex).take(limit).map(i => s"${i.kind} ${i.displayName}  ${i.symbol}")
  if r.isEmpty then s"no symbols match /$regex/ (repo not indexed? scala_repl_add index)" else r.mkString("\n")

def cmdSearch(regex: String, limit: Int): String =
  val r = searchText(regex, limit)
  val tip = if r.exists(_.contains(".scala:")) then "\n(tip: `scala_repl_add usages <Name>` resolves real symbol references in Scala code)" else ""
  (if r.isEmpty then s"no text matches for /$regex/" else r.mkString("\n")) + tip
println(s"SemanticDB project root=$semanticProjectRoot")
println("SCALA_REPL_READY")

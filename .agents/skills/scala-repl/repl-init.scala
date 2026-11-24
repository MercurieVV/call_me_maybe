import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

val semanticProjectRoot = java.nio.file.Paths.get(".").toAbsolutePath.normalize().toString
lazy val semanticIndex = SemanticIndex.fromProject(semanticProjectRoot)

def findSymbols(regex: String) =
  val rx = regex.r
  semanticIndex.symbols.valuesIterator.filter(i => rx.findFirstIn(i.displayName).isDefined).toVector

def usagesOf(regex: String, limit: Int = 50) =
  findSymbols(regex).flatMap { i =>
    semanticIndex.occurrencesOf(i.symbol).map((uri, o) => s"$uri:${o.range.fold(0)(_.startLine + 1)} ${o.role} ${i.displayName}")
  }.take(limit)
println(s"SemanticDB project root=$semanticProjectRoot")
println("SCALA_REPL_READY")

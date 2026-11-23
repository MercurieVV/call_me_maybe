import com.github.mercurievv.scalasemantic.semanticdb.SemanticIndex

val semanticProjectRoot = java.nio.file.Paths.get(".").toAbsolutePath.normalize().toString
lazy val semanticIndex = SemanticIndex.fromProject(semanticProjectRoot)
println(s"SemanticDB project root=$semanticProjectRoot")
println("SCALA_REPL_READY")

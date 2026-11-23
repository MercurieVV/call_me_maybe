//> using scala 3.8.4
//> using dep org.jetbrains.pty4j:pty4j:0.14.0
//> using dep com.lihaoyi::upickle:4.4.3

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.concurrent.TimeoutException
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import ujson.*

object ReplBridge:
  private val CoreDependency = "io.github.mercurievv::scalasemantic-core:0.9.1"
  private val ReadyMarker = "SCALA_REPL_READY"
  private val Prompt = "scala> ".getBytes(StandardCharsets.UTF_8)
  private val PromptQuietPeriod = Duration.ofMillis(200)
  private val MaxRequestBytes = 1024 * 1024

  private final case class SessionPaths(root: Path, directory: Path, socket: Path, log: Path)

  private def paths(root: Path): SessionPaths =
    val directory = root.resolve(".repl-session")
    SessionPaths(root, directory, directory.resolve("bridge.sock"), directory.resolve("bridge.log"))

  private def privatePermissions(path: Path, permissions: Set[PosixFilePermission]): Unit =
    try Files.setPosixFilePermissions(path, permissions.asJava)
    catch
      case _: UnsupportedOperationException => ()

  private def ensurePrivateDirectory(path: Path): Unit =
    Files.createDirectories(path)
    privatePermissions(
      path,
      Set(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
      )
    )

  private def removeStaleSocket(path: Path): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      if Files.isSymbolicLink(path) || !attributes.isOther then
        throw new RuntimeException(s"Refusing to replace non-socket path: $path")
      Files.delete(path)

  private def readLine(input: InputStream): String =
    val bytes = new ByteArrayOutputStream()
    var next = input.read()
    while next != -1 && next != '\n' do
      if bytes.size() >= MaxRequestBytes then
        throw new RuntimeException("Empty or oversized bridge request")
      bytes.write(next)
      next = input.read()
    if next == -1 && bytes.size() == 0 then
      throw new RuntimeException("Bridge connection closed before sending a request")
    bytes.toString(StandardCharsets.UTF_8)

  private def writeJson(channel: SocketChannel, value: Value): Unit =
    val bytes = (ujson.write(value) + "\n").getBytes(StandardCharsets.UTF_8)
    val buffer = ByteBuffer.wrap(bytes)
    while buffer.hasRemaining do channel.write(buffer)

  private def request(socketPath: Path, value: Value): Obj =
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    try
      channel.configureBlocking(true)
      channel.connect(UnixDomainSocketAddress.of(socketPath))
      writeJson(channel, value)
      val response = ujson.read(readLine(Channels.newInputStream(channel)))
      response match
        case obj: Obj => obj
        case _ => throw new RuntimeException("REPL bridge returned an invalid response")
    finally channel.close()

  private def start(root: Path): Unit =
    val session = paths(root)
    ensurePrivateDirectory(session.directory)
    if Files.exists(session.socket, LinkOption.NOFOLLOW_LINKS) then
      try
        val result = request(session.socket, Obj("op" -> Str("status")))
        if result.obj.get("ok").contains(Bool(true)) then
          println(s"REPL already running (pid ${result("pid")})")
          return
      catch
        case NonFatal(_) => removeStaleSocket(session.socket)

    val command = Seq(
      "scala-cli",
      "run",
      root.resolve(".agents/skills/scala-repl/repl-bridge.scala").toString,
      "--",
      "--root",
      root.toString,
      "serve"
    )
    val process =
      new ProcessBuilder(command*)
        .directory(root.toFile)
        .redirectInput(new java.io.File("/dev/null"))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(session.log.toFile))
        .redirectErrorStream(true)
        .start()

    val deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos
    while System.nanoTime() < deadline do
      if !process.isAlive then
        throw new RuntimeException(s"REPL bridge exited during startup:\n${readLog(session.log)}")
      try
        val result = request(session.socket, Obj("op" -> Str("status")))
        if result.obj.get("ok").contains(Bool(true)) then
          println(s"REPL started (bridge pid ${result("pid")})")
          return
      catch case NonFatal(_) => Thread.sleep(250)
    throw new RuntimeException(s"Timed out starting REPL bridge:\n${readLog(session.log)}")

  private def readLog(path: Path): String =
    if Files.exists(path) then Files.readString(path) else ""

  private def cleanOutput(output: Array[Byte], sentCode: String): String =
    val text = new String(output, StandardCharsets.UTF_8)
      .replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "")
      .replace("\r", "")
      .replace(sentCode, "")
    text
      .split("\n")
      .filterNot(_.trim == "scala>")
      .mkString("\n")
      .trim

  private final class Repl(root: Path):
    private val lock = new Object
    private val output = ArrayBuffer.empty[Byte]
    private var lastOutputAt = System.nanoTime()
    private var readFailure: Option[Throwable] = None
    private var process: Option[PtyProcess] = None

    def start(): Unit =
      val environment = System.getenv().asScala.toMap.updated("TERM", "dumb").asJava
      val command = Seq(
        "scala-cli",
        "repl",
        "--scala",
        "3.8.4",
        "--dep",
        CoreDependency,
        "--repl-init-script-file",
        root.resolve(".agents/skills/scala-repl/repl-init.scala").toString
      )
      val child =
        new PtyProcessBuilder()
          .setCommand(command.toArray)
          .setEnvironment(environment)
          .setDirectory(root.toString)
          .start()
      process = Some(child)
      val reader = new Thread(() => readOutput(child), "scala-repl-output")
      reader.setDaemon(true)
      reader.start()
      readUntil(ReadyMarker.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(180))
      readPrompt(Duration.ofSeconds(60))

    private def readOutput(child: PtyProcess): Unit =
      try
        val input = child.getInputStream
        val chunk = new Array[Byte](8192)
        var count = input.read(chunk)
        while count != -1 do
          lock.synchronized {
            output ++= chunk.take(count)
            lastOutputAt = System.nanoTime()
            lock.notifyAll()
          }
          count = input.read(chunk)
        lock.synchronized {
          readFailure = Some(new RuntimeException("Scala REPL closed its terminal"))
          lock.notifyAll()
        }
      catch
        case NonFatal(error) =>
          lock.synchronized {
            readFailure = Some(error)
            lock.notifyAll()
          }

    private def failureOrNone(): Option[Throwable] =
      readFailure.orElse(
        process.filterNot(_.isAlive).map(_ => new RuntimeException("Scala REPL exited unexpectedly"))
      )

    private def find(marker: Array[Byte]): Int =
      if marker.isEmpty then 0
      else if output.length < marker.length then -1
      else
        (0 to output.length - marker.length)
          .find(index => marker.indices.forall(offset => output(index + offset) == marker(offset)))
          .getOrElse(-1)

    private def take(count: Int): Array[Byte] =
      val result = output.take(count).toArray
      output.remove(0, count)
      result

    private def recentOutput: String =
      new String(output.takeRight(500).toArray, StandardCharsets.UTF_8)

    private def awaitChange(deadline: Long, extraWaitNanos: Long = Long.MaxValue): Unit =
      val remaining = deadline - System.nanoTime()
      if remaining <= 0 then throw new TimeoutException("Timed out waiting for Scala REPL output")
      val waitNanos = math.min(remaining, extraWaitNanos)
      val waitMillis = math.max(1L, waitNanos / 1_000_000L)
      lock.wait(waitMillis)

    private def readUntil(marker: Array[Byte], timeout: Duration): Array[Byte] =
      val deadline = System.nanoTime() + timeout.toNanos
      lock.synchronized {
        while true do
          val markerAt = find(marker)
          if markerAt >= 0 then return take(markerAt + marker.length)
          failureOrNone().foreach(error =>
            throw new RuntimeException(s"Scala REPL exited unexpectedly: ${recentOutput}", error)
          )
          try awaitChange(deadline)
          catch
            case _: TimeoutException =>
              throw new TimeoutException(s"Timed out waiting for Scala REPL output; recent PTY data: ${recentOutput}")
        Array.emptyByteArray
      }

    private def hasPromptAtEnd: Boolean =
      output.length >= Prompt.length &&
        Prompt.indices.forall(index => output(output.length - Prompt.length + index) == Prompt(index))

    private def readPrompt(timeout: Duration): Array[Byte] =
      val deadline = System.nanoTime() + timeout.toNanos
      lock.synchronized {
        while true do
          val quietRemaining = PromptQuietPeriod.toNanos - (System.nanoTime() - lastOutputAt)
          if hasPromptAtEnd && quietRemaining <= 0 then return take(output.length)
          failureOrNone().foreach(error =>
            throw new RuntimeException(s"Scala REPL exited unexpectedly: ${recentOutput}", error)
          )
          try awaitChange(deadline, if hasPromptAtEnd then quietRemaining else Long.MaxValue)
          catch
            case _: TimeoutException =>
              throw new TimeoutException(s"Timed out waiting for Scala REPL prompt; recent PTY data: ${recentOutput}")
        Array.emptyByteArray
      }

    def evaluate(code: String): String =
      if code.trim.isEmpty then throw new IllegalArgumentException("Provide a Scala expression or statement")
      if code.contains("\n") || code.contains("\r") then
        throw new IllegalArgumentException("Send one complete Scala expression per eval request")
      val child = process.getOrElse(throw new RuntimeException("Scala REPL is not running"))
      val input = child.getOutputStream
      input.write((code + "\r").getBytes(StandardCharsets.UTF_8))
      input.flush()
      val result = readPrompt(Duration.ofSeconds(60))
      val nonce = java.util.UUID.randomUUID().toString.replace("-", "")
      val marker = s"SCALA_REPL_DONE_$nonce"
      val markerCommand = s"""println("SCALA_REPL_DONE_" + "$nonce")"""
      input.write((markerCommand + "\r").getBytes(StandardCharsets.UTF_8))
      input.flush()
      readUntil(marker.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30))
      readPrompt(Duration.ofSeconds(30))
      cleanOutput(result, code)

    def stop(): Unit =
      process.foreach { child =>
        child.destroy()
        if !child.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) then
          child.destroyForcibly()
          child.waitFor()
      }
      process = None

  private def serve(root: Path): Unit =
    val session = paths(root)
    ensurePrivateDirectory(session.directory)
    removeStaleSocket(session.socket)
    val repl = new Repl(root)
    var server: ServerSocketChannel = null
    try
      repl.start()
      server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
      server.bind(UnixDomainSocketAddress.of(session.socket))
      privatePermissions(
        session.socket,
        Set(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
      )
      var stopping = false
      while !stopping do
        val channel = server.accept()
        try
          val input = Channels.newInputStream(channel)
          val response =
            try
              val requestValue = ujson.read(readLine(input))
              handleRequest(repl, requestValue) match
                case (value, shouldStop) =>
                  stopping = shouldStop
                  value
            catch
              case NonFatal(error) => Obj("ok" -> Bool(false), "error" -> Str(Option(error.getMessage).getOrElse(error.toString)))
          writeJson(channel, response)
        finally channel.close()
    finally
      repl.stop()
      if server != null then server.close()
      Files.deleteIfExists(session.socket)

  private def handleRequest(repl: Repl, value: Value): (Obj, Boolean) =
    val requestObject = value match
      case obj: Obj => obj
      case _ => throw new IllegalArgumentException("Request must be a JSON object")
    requestObject.value.get("op") match
      case Some(Str("status")) =>
        Obj(
          "ok" -> Bool(true),
          "pid" -> Str(ProcessHandle.current().pid().toString)
        ) -> false
      case Some(Str("eval")) =>
        requestObject.value.get("code") match
          case Some(Str(code)) => Obj("ok" -> Bool(true), "output" -> Str(repl.evaluate(code))) -> false
          case _ => throw new IllegalArgumentException("eval requires a string field named 'code'")
      case Some(Str("stop")) =>
        Obj("ok" -> Bool(true), "stopping" -> Bool(true)) -> true
      case Some(Str(operation)) => throw new IllegalArgumentException(s"Unknown operation: $operation")
      case _ => throw new IllegalArgumentException("Request requires a string field named 'op'")

  private def sendRequest(root: Path, value: Value): Obj =
    val session = paths(root)
    val response =
      try request(session.socket, value)
      catch
        case error: java.io.IOException =>
          throw new RuntimeException(s"REPL bridge is not running for $root: ${error.getMessage}", error)
    if !response.obj.get("ok").contains(Bool(true)) then
      throw new RuntimeException(response.obj.get("error").map(_.str).getOrElse("REPL bridge request failed"))
    response

  private def parseArgs(args: Array[String]): (Path, String, List[String]) =
    val root = args.toList match
      case "--root" :: value :: _ => Paths.get(value).toAbsolutePath.normalize()
      case _ => Paths.get(".").toAbsolutePath.normalize()
    val remaining = args.toList match
      case "--root" :: _ :: tail => tail
      case other => other
    remaining match
      case command :: tail => (root, command, tail)
      case Nil => throw new IllegalArgumentException("Expected start, status, eval, stop, or serve")

  def run(args: Array[String]): Unit =
    try
      val (root, command, rest) = parseArgs(args)
      command match
        case "start" =>
          if rest.nonEmpty then throw new IllegalArgumentException("start takes no additional arguments")
          start(root)
        case "status" =>
          if rest.nonEmpty then throw new IllegalArgumentException("status takes no additional arguments")
          val result = sendRequest(root, Obj("op" -> Str("status")))
          println(s"REPL running (bridge pid ${result("pid")})")
        case "eval" =>
          if rest.isEmpty then throw new IllegalArgumentException("eval requires a Scala expression")
          val code = rest.mkString(" ")
          val result = sendRequest(root, Obj("op" -> Str("eval"), "code" -> Str(code)))
          println(result("output").str)
        case "stop" =>
          if rest.nonEmpty then throw new IllegalArgumentException("stop takes no additional arguments")
          sendRequest(root, Obj("op" -> Str("stop")))
          println("REPL stopped")
        case "serve" =>
          if rest.nonEmpty then throw new IllegalArgumentException("serve takes no additional arguments")
          serve(root)
        case other => throw new IllegalArgumentException(s"Unknown command: $other")
    catch
      case NonFatal(error) =>
        System.err.println(s"scala-repl-bridge: ${Option(error.getMessage).getOrElse(error.toString)}")
        System.exit(1)

@main def replBridgeMain(args: String*): Unit =
  ReplBridge.run(args.toArray)

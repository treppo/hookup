package io.backchat.websocket

import java.io._
import java.util.concurrent.ConcurrentLinkedQueue
import collection.mutable
import akka.dispatch.{ Promise, ExecutionContext, Future }
import net.liftweb.json.Formats
import collection.JavaConverters._
import java.util.Queue
import collection.mutable.{ Queue ⇒ ScalaQueue }

/**
 * Companion object for the [[io.backchat.websocket.FileBuffer]]
 */
object FileBuffer {
  private object State extends Enumeration {
    val Closed, Draining, Open = Value
  }
}

/**
 * Interface trait to which fallback mechanisms must adhere to
 * It implements [[java.io.Closeable]] so you can use it as a resource
 */
trait BackupBuffer extends Closeable {

  /**
   * open the buffer
   */
  def open()

  /**
   * close the buffer, closing all external resources used by this buffer
   */
  def close()

  /**
   * Write a line to the buffer
   * @param line A [[io.backchat.websocket.WebSocketOutMessage]]
   */
  def write(line: WebSocketOutMessage)
  def drain(readLine: (WebSocketOutMessage ⇒ Future[OperationResult]))(implicit executionContext: ExecutionContext): Future[OperationResult]
}

/**
 * The default file buffer.
 * This is a file buffer that also has a memory buffer to which it writes when the file stream is
 * being read out or if a write to the file failed.
 *
 * This class has no maximum size or any limits attached to it yet.
 * So it is possible for this class to exhaust the memory and/or disk space of the machine using this buffer.
 *
 * @param file
 */
class FileBuffer private[websocket] (file: File, writeToFile: Boolean, memoryBuffer: Queue[String])(implicit wireFormat: WireFormat) extends BackupBuffer {

  def this(file: File)(implicit wireFormat: WireFormat) = this(file, true, new ConcurrentLinkedQueue[String]())

  import FileBuffer._

  @volatile private[this] var output: PrintWriter = _
  @volatile private[this] var state = State.Closed

  /**
   * Open this file buffer if not already opened.
   * This method is idempotent.
   */
  def open() { if (state == State.Closed) openFile(true) }

  @inline private[this] def openFile(append: Boolean) {
    val dir = file.getAbsoluteFile.getParentFile
    if (!dir.exists()) dir.mkdirs()
    output = new PrintWriter(new BufferedOutputStream(new FileOutputStream(file, append)), true)
    state = if (writeToFile) State.Open else State.Draining
  }

  /**
   * Write a message to the buffer.
   * When the buffer is opened it will write a new line to the file
   * When the buffer is closed it will open the buffer and then write the new line.
   * When the buffer is being drained it will buffer to memory
   * When an exception is thrown it will first buffer the message to memory and then rethrow the exception
   * @param message A [[io.backchat.websocket.WebSocketOutMessage]]
   */
  def write(message: WebSocketOutMessage): Unit = synchronized {
    val msg = wireFormat.render(message)
    try {
      state match {
        case State.Open ⇒ {
          output.println(msg)
        }
        case State.Closed ⇒ openFile(true); output.println(msg)
        case State.Draining ⇒
          memoryBuffer.offer(msg)
      }
    } catch {
      case e =>
        memoryBuffer.offer(msg)
        throw e
    }

  }

  private[this] def serializeAndSave(message: WebSocketOutMessage)(save: String ⇒ Unit) = {
    save(wireFormat.render(message))
  }

  /**
   * Drain the buffer using the `readLine` function to process each message in the buffer.
   * This method works with [[akka.dispatch.Future]] objects and needs an [[akka.dispatch.ExecutionContext]] in scope
   *
   * @param readLine A function that takes a [[io.backchat.websocket.WebSocketOutMessage]] and produces a [[akka.dispatch.Future]] of [[io.backchat.websocket.OperationResult]]
   * @param executionContext An [[akka.dispatch.ExecutionContext]]
   * @return A [[akka.dispatch.Future]] of [[io.backchat.websocket.OperationResult]]
   */
  def drain(readLine: (WebSocketOutMessage ⇒ Future[OperationResult]))(implicit executionContext: ExecutionContext): Future[OperationResult] = synchronized {
    var futures = mutable.ListBuffer[Future[OperationResult]]()
    state = State.Draining
    close()
    var input: BufferedReader = null
    var append = true
    try {
      if (file != null) {
        input = new BufferedReader(new FileReader(file))
        var line = input.readLine()
        while (line != null) { // first drain the file buffer
          if (line.nonBlank) {
            futures += readLine(wireFormat.parseOutMessage(line))
          }
          line = input.readLine()
        }
        while (!memoryBuffer.isEmpty) { // and then the memory buffer
          val line = memoryBuffer.poll()
          if (line.nonBlank)
            futures += readLine(wireFormat.parseOutMessage(line))
        }
        val res = Future.sequence(futures.toList).map(ResultList(_))
        append = false
        res
      } else Promise.successful(Success)
    } catch {
      case e ⇒
        e.printStackTrace()
        Promise.failed(e)
    } finally {
      if (input != null) {
        input.close()
      }
      openFile(append)
    }
  }

  /**
   * Closes the buffer and releases any external resources contained by this buffer.
   * This method is idempotent.
   */
  def close() {
    if (state != State.Closed) {
      if (output != null) output.close()
      output = null
      state = State.Closed
    }
  }

}
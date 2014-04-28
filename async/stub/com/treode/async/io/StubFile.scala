package com.treode.async.io

import java.io.EOFException
import java.util.{Arrays, ArrayDeque}
import scala.util.{Failure, Success}

import com.treode.async.{Async, Callback, CallbackCaptor, StubScheduler}
import com.treode.async.implicits._
import com.treode.buffer.PagedBuffer

import Async.async

class StubFile (size: Int = 0) (implicit _scheduler: StubScheduler) extends File (null) {

  private var data = new Array [Byte] (size)
  private var stack = new ArrayDeque [Callback [Unit]]

  var scheduler: StubScheduler = _scheduler
  var stop: Boolean = false
  var closed = false

  def hasLast: Boolean = !stack.isEmpty

  def last: Callback [Unit] = stack.pop()

  private def _stop (f: Callback [Unit] => Any): Async [Unit] = {
    require (!closed, "File has been closed")
    async { cb =>
      if (stop) {
        stack.push {
          case Success (v) =>
            f (cb)
          case Failure (t) =>
            cb.fail (t)
        }
      } else {
        f (cb)
      }}}

  override def fill (input: PagedBuffer, pos: Long, len: Int): Async [Unit] =
    _stop { cb =>
      try {
        require (pos >= 0)
        require (pos + len < Int.MaxValue)
        if (len <= input.readableBytes) {
          scheduler.pass (cb, ())
        } else if (data.length < pos) {
          scheduler.fail (cb, new EOFException)
        } else  {
          input.capacity (input.readPos + len)
          val p = pos.toInt + input.readableBytes
          val n = math.min (data.length - p, input.writeableBytes)
          input.writeBytes (data, pos.toInt + input.readableBytes, n)
          if (data.length < pos + len) {
            scheduler.fail (cb, new EOFException)
          } else {
            scheduler.pass (cb, ())
          }}
      } catch {
        case t: Throwable => scheduler.fail (cb, t)
      }}

  override def flush (output: PagedBuffer, pos: Long): Async [Unit] =
    _stop { cb =>
      try {
        require (pos >= 0)
        require (pos + output.readableBytes < Int.MaxValue)
        if (data.length < pos + output.readableBytes)
          data = Arrays.copyOf (data, pos.toInt + output.readableBytes)
        output.readBytes (data, pos.toInt, output.readableBytes)
        scheduler.pass (cb, ())
      } catch {
        case t: Throwable => scheduler.fail (cb, t)
      }}

  override def close(): Unit =
    closed = true

  override def toString = s"StubFile(size=${data.length})"
}

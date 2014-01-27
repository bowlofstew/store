package com.treode.disk

import com.treode.async.{AsyncIterator, Callback, callback, guard}
import com.treode.async.io.File
import com.treode.buffer.PagedBuffer

private class LogIterator private (file: File, alloc: SegmentAllocator, records: RecordRegistry)
extends AsyncIterator [(Long, Unit => Any)] {

  private val buf = new PagedBuffer (12)
  private var pos = -1L

  private def failed [A] (cb: Callback [A], t: Throwable) {
    pos = -1L
    cb.fail (t)
  }

  private def init (head: Long, cb: Callback [LogIterator]) {
    pos = head
    file.fill (buf, pos, 4, new Callback [Unit] {
      def pass (v: Unit) = cb (LogIterator.this)
      def fail (t: Throwable) = failed (cb, t)
    })
  }

  def hasNext: Boolean = pos > 0

  private def entryRead (cb: Callback [(Long, Unit => Any)]) =
    new Callback [(Int, RecordHeader, Option [Unit => Any])] {

      def pass (v: (Int, RecordHeader, Option [Unit => Any])) {
        v match {
          case (len, RecordHeader.End, None) =>
            pos = -1L
            buf.clear()
            cb (Long.MaxValue, _ => ())

          case (len, RecordHeader.Continue (num), None) =>
            val seg = alloc.allocSeg (num)
            pos = seg.pos
            buf.clear()
            records.read (file, pos, buf, this)

          case (len, RecordHeader.Entry (time, id), Some (entry)) =>
            pos += len
            cb (time, entry)

          case _ =>
            cb.fail (new MatchError)
        }
      }

      def fail (t: Throwable) = failed (cb, t)
    }

  def next (cb: Callback [(Long, Unit => Any)]): Unit =
    records.read (file, pos, buf, entryRead (cb))
}

object LogIterator {

  def apply (file: File, head: Long, alloc: SegmentAllocator, records: RecordRegistry,
      cb: Callback [LogIterator]): Unit =
    new LogIterator (file, alloc, records) .init (head, cb)

  def merge (disks: Iterable [DiskDrive], records: RecordRegistry, cb: Callback [ReplayIterator]) {

    val ordering = Ordering.by [(Long, Unit => Any), Long] (_._1)

    val allMade = new Callback [Seq [LogIterator]] {

      def pass (iters: Seq [LogIterator]): Unit =
        AsyncIterator.merge (iters.iterator, cb) (ordering)

      def fail (t: Throwable): Unit = cb.fail (t)
    }

    val oneMade = Callback.collect (disks.size, allMade)

    disks foreach (_.logIterator (records, oneMade))
  }}

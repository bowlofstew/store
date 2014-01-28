package com.treode.disk

import java.nio.file.Path
import com.treode.async.{Callback, Scheduler, guard}
import com.treode.async.io.File
import com.treode.buffer.PagedBuffer
import com.treode.pickle.Pickler

private class DiskDrive (
    val id: Int,
    val path: Path,
    file: File,
    config: DiskDriveConfig,
    scheduler: Scheduler,
    logd: LogDispatcher,
    paged: PageDispatcher,
    pages: PageRegistry) {

  val alloc = new SegmentAllocator (config)
  val logw = new LogWriter (file, alloc, scheduler, logd)
  val pagew = new PageWriter (id, file, config, alloc, scheduler, paged, pages)

  def init (cb: Callback [Unit]) {
    alloc.init()
    logw.init (cb)
  }

  def engage() {
    logd.engage (logw)
    paged.engage (pagew)
  }

  def fill (buf: PagedBuffer, pos: Long, len: Int, cb: Callback [Unit]): Unit =
    file.fill (buf, pos, len, cb)

  def checkpoint (boot: BootBlock, cb: Callback [Unit]) {
    val latch = Callback.latch (2, cb)
    val gen = boot.gen
    val superblock = SuperBlock (
        id,
        boot,
        config,
        alloc.checkpoint (gen),
        logw.checkpoint (gen),
        pagew.checkpoint (gen, latch))
    val buffer = PagedBuffer (12)
    SuperBlock.pickler.pickle (superblock, buffer)
    val pos = if ((boot.gen & 1) == 0) 0 else SuperBlockBytes
    file.flush (buffer, pos, latch)
  }

  def recover (superblock: SuperBlock, cb: Callback [Unit]) {
    val gen = superblock.boot.gen
    alloc.recover (gen, superblock.alloc)
    logw.recover (gen, superblock.log)
    pagew.recover (gen, superblock.pages, cb)
  }

  def logIterator (records: RecordRegistry, cb: Callback [LogIterator]): Unit =
    logw.iterator (records, cb)

  def close() = file.close()

  override def hashCode: Int = path.hashCode

  override def equals (other: Any): Boolean =
    other match {
      case that: DiskDrive => path == that.path
      case _ => false
    }

  override def toString = s"DiskDrive($id, $path, $config)"
}
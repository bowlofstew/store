package com.treode.store.atomic

import scala.util.{Failure, Success}

import com.treode.async.{Async, Callback, Fiber}
import com.treode.async.implicits._
import com.treode.cluster.RequestDescriptor
import com.treode.disk.RecordDescriptor
import com.treode.store.{Bytes, TxClock, TxId, TxStatus, WriteOp, log}
import com.treode.store.atomic.{WriteDeputy => WD}
import com.treode.store.locks.LockSet

import Async.{guard, supply, when}
import Callback.ignore
import WriteDirector.deliberate

private class WriteDeputy (xid: TxId, kit: AtomicKit) {
  import kit.{disks, paxos, scheduler, tables, writers}
  import kit.config.{closedLifetime, preparingTimeout}

  type WriteCallback = Callback [WriteResponse]

  val fiber = new Fiber
  var state: State = new Open

  trait State {
    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback)
    def commit (wt: TxClock, cb: WriteCallback)
    def abort (cb: WriteCallback)
    def checkpoint(): Async [Unit]
  }

  private def panic (s: State, t: Throwable): Unit =
    fiber.execute {
      if (state == s) {
        state = new Panicked (state, t)
        throw t
      }}

  private def timeout (s: State): Unit =
    fiber.delay (preparingTimeout) {
      if (state == s)
        deliberate.propose (xid.id, xid.time, TxStatus.Aborted) .run {
          case Success (TxStatus.Aborted) =>
            WriteDeputy.this.abort() run (ignore)
          case Success (TxStatus.Committed (wt)) =>
            WriteDeputy.this.commit (wt) run (ignore)
          case Failure (t) =>
            panic (s, t)
        }}

  class Open extends State {

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit =
      state = new Preparing (ct, ops, cb)

    def abort (cb: WriteCallback): Unit =
      state = new Aborting (None, cb)

    def commit (wt: TxClock, cb: WriteCallback): Unit =
      state = new Tardy (wt, cb)

    def checkpoint(): Async [Unit] =
      supply()

    override def toString = "Deputy.Open"
  }

  class Preparing (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback) extends State {

    tables.prepare (ct, ops) run {
      case Success (prep) => prepared (prep)
      case Failure (t) => failed (t)
    }

    private def _failed (t: Throwable) {
      if (state == Preparing.this) {
        log.exceptionPreparingWrite (t)
        state = new Deliberating (ops, Some (WriteResponse.Failed))
        cb.pass (WriteResponse.Failed)
        throw t
      }}

    private def failed (t: Throwable): Unit = fiber.execute {
      _failed (t)
    }

    private def failed (locks: LockSet, t: Throwable): Unit = fiber.execute {
      locks.release()
      _failed (t)
    }

    private def logged (ft: TxClock, locks: LockSet): Unit = fiber.execute {
      if (state == Preparing.this) {
        state = new Prepared (ops, ft, locks)
        cb.pass (WriteResponse.Prepared (ft))
      } else {
        locks.release()
      }}

    private def prepared (ft: TxClock, locks: LockSet): Unit = fiber.execute {
      if (state == Preparing.this) {
        WD.preparing.record (xid, ops) run {
          case Success (v) => logged (ft, locks)
          case Failure (t) => failed (locks, t)
        }
      } else {
        locks.release()
      }}

    private def collided (ks: Seq [Int]): Unit = fiber.execute {
      if (state == Preparing.this) {
        val rsp = WriteResponse.Collisions (ks.toSet)
        state = new Deliberating (ops, Some (rsp))
        cb.pass (rsp)
      }}

    private def stale(): Unit = fiber.execute {
      if (state == Preparing.this) {
        state = new Deliberating (ops, Some (WriteResponse.Advance))
        cb.pass (WriteResponse.Advance)
      }}

    private def prepared (prep: PrepareResult) {
      import PrepareResult._
      prep match {
        case Prepared (ft, locks) => prepared (ft, locks)
        case Collided (ks) => collided (ks)
        case Stale => stale()
      }}

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit = ()

    def commit (wt: TxClock, cb: WriteCallback): Unit =
      state = new Committing (wt, ops, None, cb)

    def abort (cb: WriteCallback): Unit =
      state = new Aborting (None, cb)

    def checkpoint(): Async [Unit] =
      WD.preparing.record (xid, ops)

  }

  class Prepared (ops: Seq [WriteOp], ft: TxClock, locks: LockSet) extends State {

    timeout (Prepared.this)

    private def rsp = WriteResponse.Prepared (ft)

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit =
      cb.pass (rsp)

    def commit (wt: TxClock, cb: WriteCallback): Unit =
      state = new Committing (wt, ops, Some (locks), cb)

    def abort (cb: WriteCallback): Unit =
      state = new Aborting (Some (locks), cb)

    def checkpoint(): Async [Unit] =
      WD.preparing.record (xid, ops)
  }

  class Deliberating (ops: Seq [WriteOp], rsp: Option [WriteResponse]) extends State {

    timeout (Deliberating.this)

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit =
      rsp foreach (cb.pass (_))

    def commit (wt: TxClock, cb: WriteCallback): Unit =
      new Committing (wt, ops, None, cb)

    def abort (cb: WriteCallback): Unit =
      state = new Aborting (None, cb)

    def checkpoint(): Async [Unit] =
      WD.preparing.record (xid, ops)

    override def toString = "Deputy.Deliberating"
  }

  class Tardy (wt: TxClock, cb: WriteCallback) extends State {

    timeout (Tardy.this)

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit =
      new Committing (wt, ops, None, cb)

    def commit (wt: TxClock, cb: WriteCallback) = ()

    def abort (cb: WriteCallback): Unit =
      throw new IllegalStateException

    def checkpoint(): Async [Unit] =
      supply()

    override def toString = "Deputy.Tardy"
  }

  class Committing (
      wt: TxClock,
      ops: Seq [WriteOp],
      locks: Option [LockSet],
      cb: WriteCallback) extends State {

    val gens = tables.commit (wt, ops)
    guard {
      for {
        _ <- when (locks.isEmpty) (WD.preparing.record (xid, ops))
        _ <- WD.committed.record (xid, gens, wt)
      } yield ()
    } run {
      case Success (v) => logged()
      case Failure (t) => failed (t)
    }

    private def logged(): Unit = fiber.execute {
      if (state == Committing.this) {
        state = new Committed (gens, wt)
        cb.pass (WriteResponse.Committed)
        locks foreach (_.release())
      }}

    private def failed (t: Throwable): Unit = fiber.execute {
      if (state == Committing.this) {
        state = new Panicked (this, t)
        locks foreach (_.release())
        cb.pass (WriteResponse.Failed)
        throw t
      }}

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit = ()

    def commit (wt: TxClock, cb: WriteCallback): Unit = ()

    def abort (cb: WriteCallback): Unit =
      throw new IllegalStateException

    def checkpoint(): Async [Unit] =
      WD.committed.record (xid, gens, wt)

    override def toString = "Deputy.Committing"
  }

  class Committed (gens: Seq [Long], wt: TxClock) extends State {

    fiber.delay (closedLifetime) (writers.remove (xid, WriteDeputy.this))

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit =
      cb.pass (WriteResponse.Committed)

    def commit (wt: TxClock, cb: WriteCallback): Unit =
      cb.pass (WriteResponse.Committed)

    def abort (cb: WriteCallback): Unit =
      throw new IllegalStateException

    def checkpoint(): Async [Unit] =
      WD.committed.record (xid, gens, wt)

    override def toString = "Deputy.Committed"
  }

  class Aborting (locks: Option [LockSet], cb: WriteCallback) extends State {

    guard {
      locks foreach (_.release())
      WD.aborted.record (xid)
    } run {
      case Success (v) => logged()
      case Failure (t) => failed (t)
    }

    private def logged(): Unit = fiber.execute {
      if (state == Aborting.this) {
        state = new Aborted
        cb.pass (WriteResponse.Aborted)
      }}

    private def failed (t: Throwable): Unit = fiber.execute {
      if (state == Aborting.this) {
        state = new Panicked (this, t)
        cb.pass (WriteResponse.Failed)
        throw t
      }}

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit =
      cb.pass (WriteResponse.Aborted)

    def commit (wt: TxClock, cb: WriteCallback): Unit =
      throw new IllegalStateException

    def abort (cb: WriteCallback): Unit = ()

    def checkpoint(): Async [Unit] =
      WD.aborted.record (xid)

    override def toString = "Deputy.Aborting"
  }

  class Aborted extends State {

    fiber.delay (closedLifetime) (writers.remove (xid, WriteDeputy.this))

    def status = None

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback): Unit =
      cb.pass (WriteResponse.Aborted)

    def commit (wt: TxClock, cb: WriteCallback): Unit =
      throw new IllegalStateException

    def abort (cb: WriteCallback): Unit =
      cb.pass (WriteResponse.Aborted)

    def checkpoint(): Async [Unit] =
      WD.aborted.record (xid)

    override def toString = "Deputy.Aborted"
  }

  class Panicked (s: State, thrown: Throwable) extends State {

    fiber.delay (closedLifetime) (writers.remove (xid, WriteDeputy.this))

    def checkpoint(): Async [Unit] =
      s.checkpoint()

    def prepare (ct: TxClock, ops: Seq [WriteOp], cb: WriteCallback) = ()
    def commit (wt: TxClock, cb: WriteCallback) = ()
    def abort (cb: WriteCallback) = ()

    override def toString = s"Deputy.Panicked ($thrown)"
  }

  def prepare (ct: TxClock, ops: Seq [WriteOp]): Async [WriteResponse] =
    fiber.async (state.prepare (ct, ops, _))

  def commit (wt: TxClock): Async [WriteResponse] =
    fiber.async (state.commit (wt, _))

  def abort(): Async [WriteResponse] =
    fiber.async (state.abort (_))

  def checkpoint(): Async [Unit] =
    fiber.guard (state.checkpoint())

  override def toString = state.toString
}

private object WriteDeputy {

  val prepare = {
    import AtomicPicklers._
    RequestDescriptor (
        0xFFDD52697F320AD1L,
        tuple (txId, txClock, seq (writeOp)),
        writeResponse)
  }

  val commit = {
    import AtomicPicklers._
    RequestDescriptor (0xFFF9E8BCFABDFFE6L, tuple (txId, txClock), writeResponse)
  }

  val abort = {
    import AtomicPicklers._
    RequestDescriptor (0xFF2D9D46D1F3A7F9L, txId, writeResponse)
  }

  val preparing = {
    import AtomicPicklers._
    RecordDescriptor (0x875B728C8F37467AL, tuple (txId, seq (writeOp)))
  }

  val committed = {
    import AtomicPicklers._
    RecordDescriptor (0x5A5C7DA53F8C60F6L, tuple (txId, seq (ulong), txClock))
  }

  val aborted = {
    import AtomicPicklers._
    RecordDescriptor (0xF83F939483B72F77L, txId)
  }}

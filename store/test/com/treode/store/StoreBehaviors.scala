package com.treode.store

import java.util.concurrent.{CountDownLatch, Executors}
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Random

import com.treode.async._
import com.treode.async.implicits._
import com.treode.async.stubs.{AsyncChecks, StubScheduler}
import com.treode.async.stubs.implicits._
import com.treode.pickle.Picklers
import com.treode.tags.{Intensive, Periodic}
import org.scalatest.FreeSpec

import Async.{async, guard, latch}
import Fruits.Apple
import StoreBehaviors.Accounts
import StoreTestTools._
import WriteOp._

trait StoreBehaviors {
  this: FreeSpec with AsyncChecks =>

  val T1 = TableId (0xA1)

  def aStore (newStore: StoreTestKit => Store) {

    "behave like a Store; when" - {

      "the table is empty" - {

        "reading shoud" - {

          "find 0::None for Apple##1" in {
            implicit val kit = StoreTestKit.random()
            val s = newStore (kit)
            import kit.scheduler
            s.read (1, Get (T1, Apple)) .expectSeq (0::None)
          }}

        "writing should" - {

          "allow create Apple::1 at ts=0" in {
            implicit val kit = StoreTestKit.random()
            val s = newStore (kit)
            import kit.{random, scheduler}
            val ts = s.write (random.nextTxId, 0, Create (T1, Apple, 1)) .pass
            s.expectCells (T1) (Apple##ts::1)
          }

          "allow hold Apple at ts=0" in {
            implicit val kit = StoreTestKit.random()
            val s = newStore (kit)
            import kit.{random, scheduler}
            s.write (random.nextTxId, 0, Hold (T1, Apple)) .pass
            s.expectCells (T1) ()
          }

          "allow update Apple::1 at ts=0" in {
            implicit val kit = StoreTestKit.random()
            val s = newStore (kit)
            import kit.{random, scheduler}
            val ts = s.write (random.nextTxId, 0, Update (T1, Apple, 1)) .pass
            s.expectCells (T1) (Apple##ts::1)
          }

          "allow delete Apple at ts=0" in {
            implicit val kit = StoreTestKit.random()
            val s = newStore (kit)
            import kit.{random, scheduler}
            val ts = s.write (random.nextTxId, 0, Delete (T1, Apple)) .pass
            s.expectCells (T1) (Apple##ts)
          }}}

      "the table has Apple##ts::1" - {

        def setup() (implicit kit: StoreTestKit) = {
          import kit.{random, scheduler}
          val s = newStore (kit)
          val ts = s.write (random.nextTxId, 0, Create (T1, Apple, 1)) .pass
          (s, ts)
        }

        "reading should" -  {

          "find ts::1 for Apple##ts+1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            s.read (ts+1, Get (T1, Apple)) .expectSeq (ts::1)
          }

          "find ts::1 for Apple##ts" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            s.read (ts, Get (T1, Apple)) .expectSeq (ts::1)
          }

          "find 0::None for Apple##ts-1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            s.read (ts-1, Get (T1, Apple)) .expectSeq (0::None)
          }}

        "writing should" - {

          "reject create Apple##ts-1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            val exn = s.write (random.nextTxId, ts-1, Create (T1, Apple, 1)) .fail [CollisionException]
            assertResult (Seq (0)) (exn.indexes)
          }

          "reject hold Apple##ts-1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            s.write (random.nextTxId, ts-1, Hold (T1, Apple)) .fail [StaleException]
          }

          "reject update Apple##ts-1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            s.write (random.nextTxId, ts-1, Update (T1, Apple, 1)) .fail [StaleException]
          }

          "reject delete Apple##ts-1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            s.write (random.nextTxId, ts-1, Delete (T1, Apple)) .fail [StaleException]
          }

          "allow hold Apple at ts+1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            s.write (random.nextTxId, ts+1, Hold (T1, Apple)) .pass
            s.expectCells (T1) (Apple##ts::1)
          }

          "allow hold Apple at ts" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts) = setup()
            import kit.{random, scheduler}
            s.write (random.nextTxId, ts, Hold (T1, Apple)) .pass
            s.expectCells (T1) (Apple##ts::1)
          }

          "allow update Apple::2 at ts+1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1) = setup()
            import kit.{random, scheduler}
            val ts2 = s.write (random.nextTxId, ts1+1, Update (T1, Apple, 2)) .pass
            s.expectCells (T1) (Apple##ts2::2, Apple##ts1::1)
          }

          "allow update Apple::2 at ts" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1) = setup()
            import kit.{random, scheduler}
            val ts2 = s.write (random.nextTxId, ts1, Update (T1, Apple, 2)) .pass
            s.expectCells (T1) (Apple##ts2::2, Apple##ts1::1)
          }

          "allow update Apple::1 at ts+1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1) = setup()
            import kit.{random, scheduler}
            val ts2 = s.write (random.nextTxId, ts1+1, Update (T1, Apple, 1)) .pass
            s.expectCells (T1) (Apple##ts2::1, Apple##ts1::1)
          }

          "allow update Apple::1 at ts" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1) = setup()
            import kit.{random, scheduler}
            val ts2 = s.write (random.nextTxId, ts1, Update (T1, Apple, 1)) .pass
            s.expectCells (T1) (Apple##ts2::1, Apple##ts1::1)
          }

          "allow delete Apple at ts+1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1) = setup()
            import kit.{random, scheduler}
            val ts2 = s.write (random.nextTxId, ts1+1, Delete (T1, Apple)) .pass
            s.expectCells (T1) (Apple##ts2, Apple##ts1::1)
          }

          "allow delete Apple at ts" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1) = setup()
            import kit.{random, scheduler}
            val ts2 = s.write (random.nextTxId, ts1, Delete (T1, Apple)) .pass
            s.expectCells (T1) (Apple##ts2, Apple##ts1::1)
          }}}

      "the table has Apple##ts2::2 and Apple##ts1::1" -  {

        def setup() (implicit kit: StoreTestKit) = {
          import kit.{random, scheduler}
          val s = newStore (kit)
          val ts1 = s.write (random.nextTxId, 0, Create (T1, Apple, 1)) .pass
          val ts2 = s.write (random.nextTxId, ts1, Update (T1, Apple, 2)) .pass
          s.expectCells (T1) (Apple##ts2::2, Apple##ts1::1)
          (s, ts1, ts2)
        }

        "a read should" - {

          "find ts2::2 for Apple##ts2+1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1, ts2) = setup()
            import kit.{random, scheduler}
            s.read (ts2+1, Get (T1, Apple)) .expectSeq (ts2::2)
          }

          "find ts2::2 for Apple##ts2" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1, ts2) = setup()
            import kit.{random, scheduler}
            s.read (ts2, Get (T1, Apple)) .expectSeq (ts2::2)
          }

          "find ts1::1 for Apple##ts2-1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1, ts2) = setup()
            import kit.{random, scheduler}
            s.read (ts2-1, Get (T1, Apple)) .expectSeq (ts1::1)
          }

          "find ts1::1 for Apple##ts1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1, ts2) = setup()
            import kit.{random, scheduler}
            s.read (ts1, Get (T1, Apple)) .expectSeq (ts1::1)
          }

          "find 0::None for Apple##ts1-1" in {
            implicit val kit = StoreTestKit.random()
            val (s, ts1, ts2) = setup()
            import kit.{random, scheduler}
            s.read (ts1-1, Get (T1, Apple)) .expectSeq (0::None)
          }}}}}

  def aMultithreadableStore (transfers: Int) (newStore: StoreTestKit => Store) {

    "serialize concurrent operations" taggedAs (Intensive, Periodic) in {

      multithreaded { implicit scheduler =>

        val store = newStore (StoreTestKit.multithreaded (scheduler))

        val accounts = 100
        val threads = 8
        val opening = 1000

        import scheduler.whilst

        val supply = accounts * opening
        for (i <- 0 until accounts)
          store.write (Random.nextTxId, 0, Accounts.create (i, opening)) .await()

        val brokerLatch = new CountDownLatch (threads)
        val countAuditsPassed = new AtomicInteger (0)
        val countAuditsFailed = new AtomicInteger (0)
        val countTransferPassed = new AtomicInteger (0)
        val countTransferAdvanced = new AtomicInteger (0)

        var running = true

        // Check that the sum of the account balances equals the supply
        def audit(): Async [Unit] =
          guard [Unit] {
            val ops = for (i <- 0 until accounts) yield Accounts.read (i)
            for {
              accounts <- store.read (TxClock.now, ops: _*)
              total = accounts.map (Accounts.value (_) .get) .sum
            } yield {
              if (supply == total)
                countAuditsPassed.incrementAndGet()
              else
                countAuditsFailed.incrementAndGet()
            }
          }

        // Transfer a random amount between two random accounts.
        def transfer(): Async [Unit] =
          guard [Unit] {
            val x = Random.nextInt (accounts)
            var y = Random.nextInt (accounts)
            while (x == y)
              y = Random.nextInt (accounts)
            val rops = Seq (Accounts.read (x), Accounts.read (y))
            for {
              vs <- store.read (TxClock.now, rops: _*)
              ct = vs.map (_.time) .max
              Seq (b1, b2) = vs map (Accounts.value (_) .get)
              n = Random.nextInt (b1)
              wops = Seq (Accounts.update (x, b1-n), Accounts.update (y, b2+n))
              result <- store.write (Random.nextTxId, ct, wops: _*)
            } yield {
              countTransferPassed.incrementAndGet()
            }
          } .recover {
            case _: CollisionException => throw new IllegalArgumentException
            case _: StaleException => countTransferAdvanced.incrementAndGet()
            case _: TimeoutException => ()
          }

        // Conduct many transfers.
        def broker (num: Int): Async [Unit] = {
          var i = 0
          whilst (i < transfers) {
            i += 1
            transfer()
          }}

        val brokers = {
          for {
            _ <- (0 until threads) .latch.unit foreach (broker (_))
          } yield {
            running = false
          }}

        def sleep (millis: Int): Async [Unit] =
          async (cb => scheduler.delay (millis) (cb.pass()))

        // Conduct many audits.
        val auditor = {
          whilst (running) {
            audit() .flatMap (_ => sleep (100))
          }}

        latch (brokers, auditor) .await()

        assert (countAuditsPassed.get > 0, "Expected at least one audit to pass.")
        assert (countAuditsFailed.get == 0, "Expected no audits to fail.")
        assert (countTransferPassed.get > 0, "Expected at least one transfer to pass.")
        assert (countTransferAdvanced.get > 0, "Expected at least one trasfer to advance.")
      }}}}

object StoreBehaviors {

  val Accounts = Accessor (1, Picklers.fixedInt, Picklers.fixedInt)
}

package com.treode.store.catalog

import java.nio.file.Paths
import scala.util.Random

import com.treode.async.{Async, Callback}
import com.treode.async.io.stubs.StubFile
import com.treode.cluster.{Cluster, HostId}
import com.treode.cluster.stubs.{StubActiveHost, StubHost, StubNetwork}
import com.treode.store._
import com.treode.disk.{Disks, DisksConfig, DiskGeometry}
import org.scalatest.Assertions

import Assertions.assertResult
import Callback.ignore
import CatalogTestTools._
import StubCatalogHost.{cat1, cat2}

private class StubCatalogHost (id: HostId, network: StubNetwork)
extends StubActiveHost (id, network) {
  import network.{random, scheduler}

  implicit val disksConfig = TestDisksConfig()
  implicit val storeConfig = TestStoreConfig()

  implicit val cluster: Cluster = this
  implicit val library = new Library

  implicit val recovery = Disks.recover()
  implicit val _catalogs = Catalogs.recover()

  var v1 = 0L
  var v2 = Seq.empty [Long]

  val file = new StubFile
  val geometry = TestDiskGeometry()
  val files = Seq ((Paths.get ("a"), file, geometry))

  val _launch =
    for {
      launch <- recovery.attach (files)
      catalogs <- _catalogs.launch (launch) .map (_.asInstanceOf [CatalogKit])
    } yield {
      launch.launch()
      catalogs.listen (cat1) (v1 = _)
      catalogs.listen (cat2) (v2 = _)
      (launch.disks, catalogs)
    }

  val captor = _launch.capture()
  scheduler.runTasks()
  while (!captor.wasInvoked)
    Thread.sleep (10)
  implicit val (disks, catalogs) = captor.passed

  val acceptors = catalogs.acceptors

  def setAtlas (cohorts: Cohort*) {
    val atlas = Atlas (cohorts.toArray, 1)
    library.atlas = atlas
    library.residents = atlas.residents (localId)
  }

  def issue [C] (desc: CatalogDescriptor [C]) (version: Int, cat: C) {
    import catalogs.broker.{diff, patch}
    patch (desc.id, diff (desc) (version, cat) .pass) .pass
  }}

private object StubCatalogHost {

  val cat1 = {
    import StorePicklers._
    CatalogDescriptor (0x07, fixedLong)
  }

  val cat2 = {
    import StorePicklers._
    CatalogDescriptor (0x7A, seq (fixedLong))
  }}

package com.treode.async

import com.treode.async.implicits._
import org.scalatest.FlatSpec

class RichCallbackSpec extends FlatSpec {

  class DistinguishedException extends Exception

  "RichCallback.continue" should "not invoke the callback" in {
    val captor = CallbackCaptor [Unit]
    var flag = false
    val cb = captor.continue [Unit] (_ => flag = true)
    cb.pass()
    captor.assertNotInvoked()
    assertResult (true) (flag)
  }

  it should "report an exception in the body through the callback" in {
    val captor = CallbackCaptor [Unit]
    val cb = captor.continue [Unit] (_ => throw new DistinguishedException)
    cb.pass()
    captor.failed [DistinguishedException]
  }

  it should "report an exception before the body through the callback" in {
    val captor = CallbackCaptor [Unit]
    val cb = captor.continue [Unit] (_ => ())
    cb.fail (new DistinguishedException)
    captor.failed [DistinguishedException]
  }

  "RichCallback.callback" should "invoke the callback" in {
    val captor = CallbackCaptor [Unit]
    var flag = false
    val cb = captor.callback [Unit] (_ => flag = true)
    cb.pass()
    captor.passed
    assertResult (true) (flag)
  }

  it should "report an exception in the body through the callback" in {
    val captor = CallbackCaptor [Unit]
    val cb = captor.callback [Unit] (_ => throw new DistinguishedException)
    cb.pass()
    captor.failed [DistinguishedException]
  }

  it should "report an exception before the body through the callback" in {
    val captor = CallbackCaptor [Unit]
    val cb = captor.callback [Unit] (_ => ())
    cb.fail (new DistinguishedException)
    captor.failed [DistinguishedException]
  }

  "RichCallback.defer" should "not invoke the callback" in {
    val cb = CallbackCaptor [Unit]
    var flag = false
    cb.defer (flag = true)
    cb.assertNotInvoked()
    assertResult (true) (flag)
  }

  it should "report an exception through the callback" in {
    val cb = CallbackCaptor [Unit]
    cb.defer (throw new DistinguishedException)
    cb.failed [DistinguishedException]
  }

  "RichCallback.invoke" should "invoke the callback" in {
    val cb = CallbackCaptor [Unit]
    var flag = false
    cb.invoke (flag = true)
    cb.passed
    assertResult (true) (flag)
  }

  it should "report an exception through the callback" in {
    val cb = CallbackCaptor [Unit]
    cb.invoke (throw new DistinguishedException)
    cb.failed [DistinguishedException]
  }

  "RichCallback.ensure" should "run the body on pass" in {
    val cb1 = CallbackCaptor [Unit]
    var flag = false
    val cb2 = cb1.ensure (flag = true)
    cb2.pass()
    cb1.passed
    assertResult (true) (flag)
  }

  it should "run the body on fail" in {
    val cb1 = CallbackCaptor [Unit]
    var flag = false
    val cb2 = cb1.ensure (flag = true)
    cb2.fail (new DistinguishedException)
    cb1.failed [DistinguishedException]
    assertResult (true) (flag)
  }}

/*
 * Copyright 2014 Treode, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.treode.store.tier

import com.treode.buffer.PagedBuffer
import com.treode.store.{Bound, Bytes, Fruits, Key, TxClock}
import org.scalatest.WordSpec

import Fruits.{Apple, Banana, Kiwi, Kumquat, Orange}
import TierTestTools._

class IndexPageSpec extends WordSpec {

  private def entry (key: Bytes): IndexEntry =
    new IndexEntry (key, 0, 0, 0, 0)

  private def newPage (entries: IndexEntry*): IndexPage =
    new IndexPage (Array (entries: _*))

  private def entriesEqual (expected: IndexEntry, actual: IndexEntry) {
    assertResult (expected.key) (actual.key)
    assertResult (expected.pos) (actual.pos)
  }

  private def pagesEqual (expected: IndexPage, actual: IndexPage) {
    assertResult (expected.entries.length) (actual.entries.length)
    for (i <- 0 until expected.entries.length)
      entriesEqual (expected.entries (i), actual.entries (i))
  }

  private def checkPickle (page: IndexPage) {
    val buffer = PagedBuffer (12)
    IndexPage.pickler.pickle (page, buffer)
    val result = IndexPage.pickler.unpickle (buffer)
    pagesEqual (page, result)
  }

  "A simple IndexPage" when {

    "empty" should {

      val page = newPage ()

      "find nothing" in {
        assertResult (0) (page.ceiling (Apple, 0))
      }

      "pickle and unpickle to the same value" in {
        checkPickle (page)
      }}

    "holding one entry k=kiwi" should {

      val page = newPage (entry (Kiwi))

      "find apple before kiwi" in {
        assertResult (0) (page.ceiling (Apple, 0))
      }

      "find kiwi using kiwi" in {
        assertResult (0) (page.ceiling (Kiwi, 0))
      }

      "find orange after kiwi" in {
        assertResult (1) (page.ceiling (Orange, 0))
      }

      "pickle and unpickle to the same value" in {
        checkPickle (page)
      }}

    "holding three entries" should {

      val page = newPage (
          entry (Apple),
          entry (Kiwi),
          entry (Orange))

      "find apple using apple" in {
        assertResult (0) (page.ceiling (Apple, 0))
      }

      "find kiwi using banana" in {
        assertResult (1) (page.ceiling (Banana, 0))
      }

      "find kiwi using kiwi" in {
        assertResult (1) (page.ceiling (Kiwi, 0))
      }

      "find orange using kumquat" in {
        assertResult (2) (page.ceiling (Kumquat, 0))
      }

      "find orange using orange" in {
        assertResult (2) (page.ceiling (Orange, 0))
      }

      "pickle and unpickle to the same value" in {
        checkPickle (page)
      }}}}

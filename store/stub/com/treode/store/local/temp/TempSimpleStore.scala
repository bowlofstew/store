package com.treode.store.local.temp

import com.treode.store.{SimpleStore, TableId}

private class TempSimpleStore extends SimpleStore {

  def table (id: TableId): TempSimpleTable =
    new TempSimpleTable
}
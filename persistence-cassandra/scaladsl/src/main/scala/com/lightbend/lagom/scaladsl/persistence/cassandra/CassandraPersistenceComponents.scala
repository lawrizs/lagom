/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.scaladsl.persistence.cassandra

import com.lightbend.lagom.internal.scaladsl.persistence.cassandra.CassandraPersistentEntityRegistry
import com.lightbend.lagom.internal.scaladsl.persistence.cassandra.CassandraReadSideImpl
import com.lightbend.lagom.internal.scaladsl.persistence.cassandra.ScaladslCassandraOffsetStore
import com.lightbend.lagom.scaladsl.persistence.PersistenceComponents
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import com.lightbend.lagom.scaladsl.persistence.ReadSidePersistenceComponents
import com.lightbend.lagom.scaladsl.persistence.WriteSidePersistenceComponents
import com.lightbend.lagom.internal.persistence.cassandra.CassandraReadSideSettings
import com.lightbend.lagom.internal.persistence.cassandra.CassandraOffsetStore
import com.lightbend.lagom.spi.persistence.OffsetStore

/**
 * Persistence Cassandra components (for compile-time injection).
 */
trait CassandraPersistenceComponents
    extends PersistenceComponents
    with ReadSideCassandraPersistenceComponents
    with WriteSideCassandraPersistenceComponents

/**
 * Write-side persistence Cassandra components (for compile-time injection).
 */
trait WriteSideCassandraPersistenceComponents extends WriteSidePersistenceComponents {
  override lazy val persistentEntityRegistry: PersistentEntityRegistry =
    new CassandraPersistentEntityRegistry(actorSystem)
}

/**
 * Read-side persistence Cassandra components (for compile-time injection).
 */
trait ReadSideCassandraPersistenceComponents extends ReadSidePersistenceComponents {
  lazy val cassandraSession: CassandraSession                 = new CassandraSession(actorSystem)
  lazy val testCasReadSideSettings: CassandraReadSideSettings = new CassandraReadSideSettings(actorSystem)

  private[lagom] lazy val cassandraOffsetStore: CassandraOffsetStore =
    new ScaladslCassandraOffsetStore(actorSystem, cassandraSession, testCasReadSideSettings, readSideConfig)(
      executionContext
    )
  lazy val offsetStore: OffsetStore = cassandraOffsetStore

  lazy val cassandraReadSide: CassandraReadSide =
    new CassandraReadSideImpl(actorSystem, cassandraSession, testCasReadSideSettings, cassandraOffsetStore)
}

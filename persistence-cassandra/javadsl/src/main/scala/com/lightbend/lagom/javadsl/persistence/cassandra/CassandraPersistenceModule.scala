/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.javadsl.persistence.cassandra

import akka.actor.ActorSystem
import com.lightbend.lagom.internal.javadsl.persistence.cassandra._
import com.lightbend.lagom.internal.persistence.cassandra.CassandraOffsetStore
import com.lightbend.lagom.internal.persistence.cassandra.CassandraReadSideSettings
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRegistry
import com.lightbend.lagom.spi.persistence.OffsetStore
import javax.annotation.PostConstruct
import javax.inject.Inject
import play.api.Configuration
import play.api.Environment
import play.api.inject._


/**
 * Guice module for the Persistence API.
 */
class CassandraPersistenceModule extends Module {
  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = Seq(
    bind[CassandraPersistenceModule.InitServiceLocatorHolder].toSelf.eagerly(),
    bind[PersistentEntityRegistry].to[CassandraPersistentEntityRegistry],
    bind[CassandraSession].toSelf,
    bind[CassandraReadSide].to[CassandraReadSideImpl],
    bind[CassandraReadSideSettings].toSelf,
    bind[CassandraOffsetStore].to[JavadslCassandraOffsetStore],
    bind[OffsetStore].to(bind[CassandraOffsetStore])
  )
}

private[lagom] object CassandraPersistenceModule {
  class InitServiceLocatorHolder @Inject() (system: ActorSystem, injector: Injector) {
    // Guice doesn't support this, but other DI frameworks do.
    @PostConstruct
    def init(): Unit = {
    }
  }
}

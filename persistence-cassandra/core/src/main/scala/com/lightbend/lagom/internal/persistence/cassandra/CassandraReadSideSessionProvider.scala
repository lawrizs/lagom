/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.internal.persistence.cassandra

import akka.Done
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.stream.alpakka.cassandra.scaladsl.{ CassandraSession => AkkaScaladslCassandraSession }
import akka.stream.alpakka.cassandra.CqlSessionProvider
import com.datastax.oss.driver.api.core.CqlSession

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
 * Internal API
 */
private[lagom] object CassandraReadSideSessionProvider {
  def apply(
      system: ActorSystem,
      executionContext: ExecutionContext
  ): AkkaScaladslCassandraSession = {
    import akka.util.Helpers.Requiring

    import scala.collection.JavaConverters._ // implicit asScala conversion
    import scala.compat.java8.FutureConverters._

    val cfg = system.settings.config.getConfig("lagom.persistence.read-side.cassandra")
    val replicationStrategy: String = getReplicationStrategy(
      cfg.getString("replication-strategy"),
      cfg.getInt("replication-factor"),
      cfg.getStringList("data-center-replication-factors").asScala.toSeq
    )

    val keyspaceAutoCreate: Boolean = cfg.getBoolean("keyspace-autocreate")
    val keyspace: String = cfg
      .getString("keyspace")
      .requiring(
        !keyspaceAutoCreate || _ > "",
        "'keyspace' configuration must be defined, or use keyspace-autocreate=off"
      )

    def init(session: CqlSession): Future[Done] = {
      implicit val ec = executionContext
      if (keyspaceAutoCreate) {
        val result1 =
          session.executeAsync(s"""
            CREATE KEYSPACE IF NOT EXISTS $keyspace
            WITH REPLICATION = { 'class' : $replicationStrategy }
            """).toScala
        result1
          .flatMap { _ =>
            session.executeAsync(s"USE $keyspace;").toScala
          }
          .map(_ => Done)
      } else if (keyspace != "")
        session.executeAsync(s"USE $keyspace;").toScala.map(_ => Done)
      else
        Future.successful(Done)
    }

    val metricsCategory = "lagom-" + system.name

    // using the scaladsl API because the init function
    new AkkaScaladslCassandraSession(
      system,
      CqlSessionProvider(system.asInstanceOf[ExtendedActorSystem], cfg),
      executionContext,
      Logging.getLogger(system, this.getClass),
      metricsCategory,
      init,
      onClose = () => (),
    )
  }

  def getReplicationStrategy(
      strategy: String,
      replicationFactor: Int,
      dataCenterReplicationFactors: Seq[String]
  ): String = {

    def getDataCenterReplicationFactorList(dcrfList: Seq[String]): String = {
      val result: Seq[String] = dcrfList match {
        case null | Nil =>
          throw new IllegalArgumentException(
            "data-center-replication-factors cannot be empty when using NetworkTopologyStrategy."
          )
        case dcrfs =>
          dcrfs.map { dataCenterWithReplicationFactor =>
            dataCenterWithReplicationFactor.split(":") match {
              case Array(dataCenter, replicationFactor) =>
                s"'$dataCenter':$replicationFactor"
              case msg =>
                throw new IllegalArgumentException(
                  s"A data-center-replication-factor must have the form [dataCenterName:replicationFactor] but was: $msg."
                )
            }
          }
      }
      result.mkString(",")
    }

    strategy.toLowerCase() match {
      case "simplestrategy" =>
        s"'SimpleStrategy','replication_factor':$replicationFactor"
      case "networktopologystrategy" =>
        s"'NetworkTopologyStrategy',${getDataCenterReplicationFactorList(dataCenterReplicationFactors)}"
      case unknownStrategy =>
        throw new IllegalArgumentException(s"$unknownStrategy as replication strategy is unknown and not supported.")
    }
  }
}

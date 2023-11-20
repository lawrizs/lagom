/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.internal.persistence.cassandra

import akka.actor.ActorSystem
import akka.stream.alpakka.cassandra.CqlSessionProvider
import akka.stream.alpakka.cassandra.DriverConfigLoaderFromConfig
import com.datastax.oss.driver.api.core.`type`.reflect.GenericType
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import com.datastax.oss.driver.api.core.context.DriverContext
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.metadata.Metadata
import com.datastax.oss.driver.api.core.metrics.Metrics
import com.datastax.oss.driver.api.core.session.Request
import com.datastax.oss.driver.api.core.CqlIdentifier
import com.datastax.oss.driver.api.core.CqlSession
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import play.api.Logger

import java.lang
import java.util.Optional
import java.util.concurrent.CompletionStage
import scala.collection.immutable
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.collection.JavaConverters._

/**
 * Internal API
 *
 * The implementation of the ServiceLocator-based Cassandra session provided, which is based on the
 * https://github.com/akka/alpakka/blob/v6.0.1/cassandra/src/main/scala/akka/stream/alpakka/cassandra/AkkaDiscoverySessionProvider.scala
 *
 */
private[lagom] final class ServiceLocatorSessionProvider(system: ActorSystem, config: Config)
    extends CqlSessionProvider {
  private val log = Logger(getClass)

  /** Check whether we run in CosmosDB compatibility mode */
  private val isCosmosDBCompat = system.settings.config.getBoolean("akka.persistence.cassandra.compatibility.cosmosdb")

  /** Build the CqlSession from the provided configs and apply needed customizations */
  private def buildSession(driverConfigLoader: DriverConfigLoader)(implicit ec: ExecutionContext): Future[CqlSession] =
    CqlSession
      .builder()
      .withConfigLoader(driverConfigLoader)
      .buildAsync()
      .toScala

  override def connect()(implicit ec: ExecutionContext): Future[CqlSession] = {
    val serviceConfig = config.getConfig("service-discovery")
    val serviceName   = serviceConfig.getString("name")

    if (serviceName.isEmpty) {
      // When service name is empty, just use default settings
      val driverConfig       = CqlSessionProvider.driverConfig(system, config)
      val driverConfigLoader = DriverConfigLoaderFromConfig.fromConfig(driverConfig)
      buildSession(driverConfigLoader)
    } else
      // OK, service name is given, look-up for the service and init the session
      lookupContactPoints(serviceName = serviceName).flatMap { contactPoints =>
        val driverConfigWithContactPoints = ConfigFactory.parseString(s"""
       basic.contact-points = [${contactPoints.mkString("\"", "\", \"", "\"")}]
       """).withFallback(CqlSessionProvider.driverConfig(system, config))
        val driverConfigLoader            = DriverConfigLoaderFromConfig.fromConfig(driverConfigWithContactPoints)
        buildSession(driverConfigLoader)
      }
  }

  def lookupContactPoints(
      serviceName: String
  )(implicit ec: ExecutionContext): Future[immutable.Seq[String]] = {
    ServiceLocatorHolder(system).serviceLocatorEventually.flatMap { serviceLocatorAdapter =>
      serviceLocatorAdapter.locateAll(serviceName).map {
        case Nil => throw new NoContactPointsException(s"No contact points for [$serviceName]")
        case uris =>
          log.debug(s"Found Cassandra contact points: $uris")

          // URIs must be all valid
          uris.foreach { uri =>
            require(uri.getHost != null, s"missing host in $uri for Cassandra contact points $serviceName")
            require(uri.getPort != -1, s"missing port in $uri for Cassandra contact points $serviceName")
          }

          // This is based on https://github.com/akka/alpakka/blob/v6.0.1/cassandra/src/main/scala/akka/stream/alpakka/cassandra/AkkaDiscoverySessionProvider.scala
          uris.map { uri =>
            uri.getHost + ":" + uri.getPort
          }
      }
    }
  }
}

private[lagom] final class NoContactPointsException(msg: String) extends RuntimeException(msg) with NoStackTrace

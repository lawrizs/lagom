/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.internal.persistence.testkit

import scala.collection.JavaConverters._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.time.ZoneOffset

private[lagom] object PersistenceTestConfig {
  lazy val BasicConfigMap: Map[String, AnyRef] = Map(
    "lagom.akka.management.enabled" -> "off",
    "akka.actor.provider"           -> "local",
  )

  lazy val BasicConfig: Config = ConfigFactory.parseMap(BasicConfigMap.asJava)

  lazy val ClusterConfigMap: Map[String, AnyRef] =
    BasicConfigMap ++
      Map(
        "akka.actor.provider"                   -> "cluster",
        "akka.remote.artery.canonical.port"     -> "0",
        "akka.remote.artery.canonical.hostname" -> "127.0.0.1",
        // needed when users opt-out from Artery
        "akka.remote.classic.netty.tcp.port"            -> "0",
        "akka.remote.classic.netty.tcp.hostname"        -> "127.0.0.1",
        "lagom.cluster.join-self"                       -> "on",
        "lagom.cluster.exit-jvm-when-system-terminated" -> "off",
        "lagom.cluster.bootstrap.enabled"               -> "off"
      )

  lazy val ClusterConfig: Config = ConfigFactory.parseMap(ClusterConfigMap.asJava)

  /** Return the Cassandra config Map with the default Cluster settings */
  def cassandraConfigMap(keyspacePrefix: String, cassandraPort: Int): Map[String, AnyRef] =
    ClusterConfigMap ++ cassandraConfigMapOnly(keyspacePrefix, cassandraPort)

  /** Return the Cassandra config with the default Cluster settings */
  def cassandraConfig(keyspacePrefix: String, cassandraPort: Int): Config =
    ConfigFactory.parseMap(cassandraConfigMap(keyspacePrefix, cassandraPort).asJava)

  /**
   * Return the Cassandra config Map without the default Cluster settings
   * Specially useful for multi-jvm tests that configures the cluster manually
   */
  def cassandraConfigMapOnly(keyspacePrefix: String, cassandraPort: Int): Map[String, AnyRef] =
    Map(
      "akka.loglevel"                                                       -> "INFO",
      "akka.persistence.journal.plugin"                                     -> "akka.persistence.cassandra.journal",
      "akka.persistence.snapshot-store.plugin"                              -> "akka.persistence.cassandra.snapshot",
      "akka.test.single-expect-default"                                     -> "5s",
      "datastax-java-driver.basic.contact-points.0"                         -> s"127.0.0.1:${cassandraPort.toString}",
      "datastax-java-driver.basic.load-balancing-policy.local-datacenter"   -> "datacenter1",
      "datastax-java-driver.advanced.reconnect-on-init"                     -> "false",
      "datastax-java-driver.basic.request.timeout"                          -> "5s",
      "lagom.persistence.read-side.cassandra.keyspace-autocreate"           -> "true",
      "lagom.persistence.read-side.cassandra.tables-autocreate"             -> "true",
      "akka.persistence.cassandra.journal.keyspace-autocreate"              -> "true",
      "akka.persistence.cassandra.journal.tables-autocreate"                -> "true",
      "akka.persistence.cassandra.journal.keyspace"                         -> keyspacePrefix,
      "akka.persistence.cassandra.events-by-tag.eventual-consistency-delay" -> "2s",
      "akka.persistence.cassandra.events-by-tag.first-time-bucket"          -> firstTimeBucket,
      "akka.persistence.cassandra.snapshot.keyspace"                        -> keyspacePrefix,
      "akka.persistence.cassandra.snapshot.keyspace-autocreate"             -> "true",
      "akka.persistence.cassandra.snapshot.tables-autocreate"               -> "true",
      "lagom.persistence.read-side.cassandra.keyspace"                      -> s"${keyspacePrefix}_read",
    )

  private def firstTimeBucket: String = {
    val today                                = LocalDateTime.now(ZoneOffset.UTC)
    val firstBucketFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HH:mm")
    today.minusHours(3).format(firstBucketFormat)
  }

  /**
   * Return the Cassandra config without the default Cluster settings
   * Specially useful for multi-jvm tests that configures the cluster manually
   */
  def cassandraConfigOnly(keyspacePrefix: String, cassandraPort: Int): Config =
    ConfigFactory.parseMap(cassandraConfigMapOnly(keyspacePrefix, cassandraPort).asJava)

  lazy val JdbcConfigMap: Map[String, AnyRef] =
    ClusterConfigMap ++
      Map(
        "akka.persistence.journal.plugin"        -> "jdbc-journal",
        "akka.persistence.snapshot-store.plugin" -> "jdbc-snapshot-store"
      )

  lazy val JdbcConfig: Config = ConfigFactory.parseMap(JdbcConfigMap.asJava)
}

package com.lightbend.lagom.sbt

import sbt._
import com.lightbend.lagom.core.LagomVersion

/** This defines all Akka dependencies, with specific versions compatible to this Lagom instance */
object AkkaDeps {

  // Here, we can override Akka, Play, Akka gRPC versions
  val playVersion = LagomVersion.play
  val akkaVersion = LagomVersion.akka // Works with 2.7.0, but taking a lower one, since Play 2.8.18 uses 2.8.18
  val akkaHttpVersion = LagomVersion.akkaHttp // Works with 10.4.0, but selected 10.2.10 to match the Akka version


  /** All relevant Akka, Akka HTTP, Play, Testkit modules */
  def lagomAkkaDeps: Seq[sbt.ModuleID] =
    modulesIdsAkka ++ modulesIdsAkkaHttp ++ moduleIdsPlay ++ modulesIdsAkkaTestkit

  /** Akka module Ids we use */
  def modulesIdsAkka: scala.Seq[ModuleID] = Seq(
    "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
    "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-coordination" % akkaVersion,
    "com.typesafe.akka" %% "akka-distributed-data" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
    "com.typesafe.akka" %% "akka-protobuf" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion
  )

  /** Akka http module Ids we use */
  def modulesIdsAkkaHttp: scala.Seq[ModuleID] = Seq(
    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http2-support" % akkaHttpVersion
  )

  def modulesIdsAkkaTestkit: scala.Seq[ModuleID] = Seq(
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion
  )

  /** Play module Ids we use */
  def moduleIdsPlay: scala.Seq[ModuleID] = Seq(
    "com.typesafe.play" %% "play" % playVersion
  )

}

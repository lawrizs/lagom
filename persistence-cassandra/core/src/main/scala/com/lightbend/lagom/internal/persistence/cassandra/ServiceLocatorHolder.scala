/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.internal.persistence.cassandra

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Success
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import java.net.URI

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/**
 * The `ServiceLocatorHolder` is used by the `ServiceLocatorSessionProvider` to
 * locate the contact point address of the Cassandra cluster via the `ServiceLocator`.
 * The `ServiceLocator` is provided via dependency injection and `ServiceLocatorSessionProvider`
 * is created by `akka-persistence-cassandra` from configuration. To bridge those two worlds
 * this Akka extension is used so that the `ServiceLocatorSessionProvider` can use the
 * `ServiceLocator`.
 */
private[lagom] object ServiceLocatorHolder extends ExtensionId[ServiceLocatorHolder] with ExtensionIdProvider {
  override def get(system: ActorSystem): ServiceLocatorHolder = super.get(system)

  override def lookup = ServiceLocatorHolder

  override def createExtension(system: ExtendedActorSystem): ServiceLocatorHolder =
    new ServiceLocatorHolder(system)

  // Using 10 seconds as timeout, to accommodate the following issue:
  // https://github.com/lagom/lagom/issues/1667
  val TIMEOUT = 10.seconds
}

private[lagom] class ServiceLocatorHolder(system: ExtendedActorSystem) extends Extension {
  private val promisedServiceLocator = Promise[ServiceLocatorAdapter]()

  import ServiceLocatorHolder.TIMEOUT

  private implicit val exCtx = system.dispatcher
  private val delayed = {
    akka.pattern.after(TIMEOUT, using = system.scheduler) {
      Future.failed(
        new NoServiceLocatorException(
          s"Timed out after $TIMEOUT while waiting for a ServiceLocator. Have you configured one?"
        )
      )
    }
  }

  def serviceLocatorEventually: Future[ServiceLocatorAdapter] =
    Future.firstCompletedOf(Seq(promisedServiceLocator.future, delayed))

  def setServiceLocator(locator: ServiceLocatorAdapter): Unit = {
    promisedServiceLocator.complete(Success(locator))
  }
}

private[lagom] final class NoServiceLocatorException(msg: String) extends RuntimeException(msg) with NoStackTrace

/**
 * scaladsl and javadsl specific implementations
 */
private[lagom] trait ServiceLocatorAdapter {
  def locateAll(name: String): Future[List[URI]]
}

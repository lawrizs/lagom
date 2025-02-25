/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.internal.scaladsl.persistence.cassandra

import akka.persistence.query.Offset
import akka.stream.ActorAttributes
import akka.stream.scaladsl.Flow
import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchStatementBuilder
import com.datastax.oss.driver.api.core.cql.BatchType
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.lightbend.lagom.internal.persistence.cassandra.CassandraOffsetDao
import com.lightbend.lagom.internal.persistence.cassandra.CassandraOffsetStore
import com.lightbend.lagom.internal.persistence.cassandra.CassandraReadSideSettings
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor.ReadSideHandler
import com.lightbend.lagom.scaladsl.persistence._
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraSession
import org.slf4j.LoggerFactory

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.collection.JavaConverters._

/**
 * Internal API
 */
private[cassandra] abstract class CassandraReadSideHandler[Event <: AggregateEvent[Event], Handler](
    session: CassandraSession,
    readSideSettings: CassandraReadSideSettings,
    handlers: Map[Class[_ <: Event], Handler],
    dispatcher: String
)(implicit ec: ExecutionContext)
    extends ReadSideHandler[Event] {
  private val log = LoggerFactory.getLogger(this.getClass)

  protected def invoke(handler: Handler, event: EventStreamElement[Event]): Future[immutable.Seq[BoundStatement]]

  protected def offsetStatement(offset: Offset): BoundStatement

  override def handle(): Flow[EventStreamElement[Event], Done, NotUsed] = {
    def executeStatements(statements: Seq[BoundStatement]): Future[Done] = {
      // statements is never empty, there is at least the store offset statement
      // for simplicity we just use batch api (even if there is only one)
      val batch = BatchStatement
        .newInstance(BatchType.UNLOGGED)
        .setExecutionProfileName(readSideSettings.writeProfile)
        .addAll(statements.asJava)
      session.executeWriteBatch(batch)
    }

    Flow[EventStreamElement[Event]]
      .mapAsync(parallelism = 1) { elem =>
        val eventClass = elem.event.getClass

        val handler =
          handlers.getOrElse(
            // lookup handler
            eventClass,
            // fallback to empty handler if none
            {
              if (log.isDebugEnabled()) log.debug("Unhandled event [{}]", eventClass.getName)
              CassandraAutoReadSideHandler.emptyHandler.asInstanceOf[Handler]
            }
          )

        for {
          statements <- invoke(handler, elem)
          _          <- executeStatements(statements)
          // important: only commit offset once read view
          // statements has completed successfully
          _ <- executeStatements(offsetStatement(elem.offset) :: Nil)
        } yield Done
      }
      .withAttributes(ActorAttributes.dispatcher(dispatcher))
  }
}

/**
 * Internal API
 */
private[cassandra] object CassandraAutoReadSideHandler {
  type Handler[Event] = (EventStreamElement[_ <: Event]) => Future[immutable.Seq[BoundStatement]]

  def emptyHandler[Event]: Handler[Event] =
    (_) => Future.successful(immutable.Seq.empty[BoundStatement])
}

/**
 * Internal API
 */
private[cassandra] final class CassandraAutoReadSideHandler[Event <: AggregateEvent[Event]](
    session: CassandraSession,
    readSideSettings: CassandraReadSideSettings,
    offsetStore: CassandraOffsetStore,
    handlers: Map[Class[_ <: Event], CassandraAutoReadSideHandler.Handler[Event]],
    globalPrepareCallback: () => Future[Done],
    prepareCallback: AggregateEventTag[Event] => Future[Done],
    readProcessorId: String,
    dispatcher: String
)(implicit ec: ExecutionContext)
    extends CassandraReadSideHandler[Event, CassandraAutoReadSideHandler.Handler[Event]](
      session,
      readSideSettings,
      handlers,
      dispatcher
    ) {
  import CassandraAutoReadSideHandler.Handler

  @volatile
  private var offsetDao: CassandraOffsetDao = _

  protected override def invoke(
      handler: Handler[Event],
      element: EventStreamElement[Event]
  ): Future[immutable.Seq[BoundStatement]] =
    handler
      .asInstanceOf[EventStreamElement[Event] => Future[immutable.Seq[BoundStatement]]]
      .apply(element)

  override def globalPrepare(): Future[Done] = {
    globalPrepareCallback.apply()
  }

  override def offsetStatement(offset: Offset): BoundStatement =
    offsetDao.bindSaveOffset(offset)

  override def prepare(tag: AggregateEventTag[Event]): Future[Offset] = {
    for {
      _   <- prepareCallback.apply(tag)
      dao <- offsetStore.prepare(readProcessorId, tag.tag)
    } yield {
      offsetDao = dao
      dao.loadedOffset
    }
  }
}

/*
 * Copyright (C) Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.internal.scaladsl.persistence.cassandra

import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.stream.ActorAttributes
import akka.stream.scaladsl.Flow
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BatchableStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.lightbend.lagom.internal.persistence.cassandra.CassandraOffsetDao
import com.lightbend.lagom.internal.persistence.cassandra.CassandraOffsetStore
import com.lightbend.lagom.internal.persistence.cassandra.CassandraReadSideSettings
import com.lightbend.lagom.scaladsl.persistence.ReadSideProcessor.ReadSideHandler
import com.lightbend.lagom.scaladsl.persistence._
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraSession
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
 * Internal API
 */
private[cassandra] abstract class CassandraReadSideHandler[Event <: AggregateEvent[Event], Handler](
    system: ActorSystem,
    session: CassandraSession,
    readSideSettings: CassandraReadSideSettings,
    handlers: Map[Class[_ <: Event], Handler],
    dispatcher: String
)(implicit ec: ExecutionContext)
    extends ReadSideHandler[Event] {
  private val log = LoggerFactory.getLogger(this.getClass)

  /** Check whether we run in CosmosDB compatibility mode */
  private val isCosmosDBCompat = system.settings.config.getBoolean("akka.persistence.cassandra.compatibility.cosmosdb")

  protected def invoke(handler: Handler, event: EventStreamElement[Event]): Future[immutable.Seq[BatchStatement]]

  protected def offsetStatement(offset: Offset): BoundStatement

  override def handle(): Flow[EventStreamElement[Event], Done, NotUsed] = {
    /* Execute batches in non-compatibility mode */
    def executeBatches(batches: Seq[BatchStatement]): Future[Done] = {
      // statements is never empty, there is at least the store offset statement
      // for simplicity we just use batch api (even if there is only one)
      Future
        .sequence(
          batches
            .filter(_.size() > 0)
            .map(stmt => {
              // Generate a statement to execute, by setting the write profile
              val batch = BatchStatement
                .builder(stmt)
                .setExecutionProfileName(readSideSettings.writeProfile)
                .build()

              // Execute the batch
              session.executeWriteBatch(batch)
            })
        )
        .map(_ => Done)
    }

    /* Execute batches in compatibility (CosmosDB) mode */
    def executeBatchesCosmosDBCompat(batches: Seq[BatchStatement]): Future[Done] = {

      /** Chain futures, so they run sequentially, i.e., one after another. */
      def chainFutures[T](futureLambdas: Seq[() => Future[T]])(
          implicit
          ec: ExecutionContext
      ): Future[Seq[T]] = {

        def chain(futureLambdas: List[() => Future[T]], acc: List[T]): Future[List[T]] = futureLambdas match {
          case Nil          => Future.successful(acc)
          case head :: tail => head.apply().flatMap(v => chain(tail, acc :+ v))
        }

        chain(futureLambdas.toList, Nil)
      }

      // Process the batches

      for {
        // Get the Cql Context
        cqlSession <- session.delegate.underlying()

        // Get the driver context
        driverContext = cqlSession.getContext

        // Generate a new set of batches
        newBatches = batches.flatMap { b =>
          // Break batches which are larger than 100 statements
          val brokenInCount =
            if (b.size() <= 100) Seq(b) // All good, keep the batch
            else {
              // Group statements into the groups of 100
              val stmtsGrouped = b.asScala.toSeq.grouped(100).toSeq
              // Construct a new batch for each group
              stmtsGrouped.map(stmts =>
                BatchStatement
                  .newInstance(b.getBatchType)
                  .addAll(stmts.asJavaCollection)
              )
            }

          // Break the batch queries in size
          val brokenInSize = brokenInCount.flatMap { b =>
            if (b.computeSizeInBytes(driverContext) <= 2000000 /* 2 MB*/ ) Seq(b)
            else {
              // Extract the statements
              val stmts = b.asScala.toSeq

              // Group the statements by size
              stmts.foldLeft(Seq(BatchStatement.newInstance(b.getBatchType))) {
                case (head :: tail, stmt) =>
                  // Build a new batch with the statement
                  val batchWithStmt = BatchStatement
                    .builder(head)
                    .addStatement(stmt)
                    .build()

                  // Check if the new batch is below 2 MB
                  if (batchWithStmt.computeSizeInBytes(driverContext) <= 2000000 /* 2 MB*/ )
                    batchWithStmt :: tail // All good, update the head batch
                  else {
                    // Add a new batch as a head
                    val newHeadBatch = BatchStatement
                      .newInstance(b.getBatchType)
                      .add(stmt)

                    // Return a new head batch
                    newHeadBatch :: head :: tail
                  }
              }
            }
          }

          // Return batch queries broken by count and size
          brokenInSize
        }

        // Start executing newBatches
        _ <- {

          // Construct a seq of futures for execution
          val futures = newBatches.flatMap(b => {
            // Unwrap the statement, if the batch has 1 statement
            if (b.size() == 0) None // Do nothing for batches of size 0
            else if (b.size() == 1) {
              b.asScala.headOption.map(stmt => () => session.executeWrite(stmt))
            } else {

              // Generate a statement to execute, by setting the write profile
              val batch = BatchStatement
                .builder(b)
                .setExecutionProfileName(readSideSettings.writeProfile)
                .build()

              val job = session
                .executeWriteBatch(batch)
                .recoverWith {
                  case e: Exception =>
                    this.log.warn(
                      "Failed to execute a read-side batch query in the CosmosDB compatibility mode. Running statements one by one.",
                      e
                    )

                    // Extract statements
                    val stmts = batch.asScala.toList

                    // Chain statements one by one
                    chainFutures(stmts.map(s => () => session.executeWrite(s)))
                      .map(_ => Done)

                }

              Some(() => job) // Execute as a batch job
            }
          })

          // Execute futures one after the other, not to overload the CosmosDB
          chainFutures(futures)
        }

      } yield Done
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
          // Execute the batches in compatibility mode or not
          _ <- if (this.isCosmosDBCompat) executeBatchesCosmosDBCompat(statements)
          else executeBatches(statements)
          // important: only commit offset once read view
          // statements has completed successfully
          _ <- session.executeWrite(offsetStatement(elem.offset))
        } yield Done
      }
      .withAttributes(ActorAttributes.dispatcher(dispatcher))
  }
}

/**
 * Internal API
 */
private[cassandra] object CassandraAutoReadSideHandler {
  type Handler[Event] = (EventStreamElement[_ <: Event]) => Future[immutable.Seq[BatchStatement]]

  def emptyHandler[Event]: Handler[Event] =
    (_) => Future.successful(immutable.Seq.empty[BatchStatement])
}

/**
 * Internal API
 */
private[cassandra] final class CassandraAutoReadSideHandler[Event <: AggregateEvent[Event]](
    system: ActorSystem,
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
      system,
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
  ): Future[immutable.Seq[BatchStatement]] =
    handler
      .asInstanceOf[EventStreamElement[Event] => Future[immutable.Seq[BatchStatement]]]
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

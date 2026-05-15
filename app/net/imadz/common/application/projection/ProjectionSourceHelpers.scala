package net.imadz.common.application.projection

import akka.NotUsed
import akka.persistence.query.Offset
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object ProjectionSourceHelpers {

  /** Wrap a SourceProvider so the underlying eventsByTag stream completes
   *  after `timeout` of inactivity — forcing the projection to restart from
   *  the last committed offset and pick up new events.
   */
  def withIdleTimeout[Envelope](
    provider: SourceProvider[Offset, Envelope],
    timeout: FiniteDuration = 300.seconds
  )(implicit ec: ExecutionContext): SourceProvider[Offset, Envelope] =
    new SourceProvider[Offset, Envelope] {
      override def source(offset: () => Future[Option[Offset]]): Future[Source[Envelope, NotUsed]] =
        provider.source(offset).map(_.idleTimeout(timeout))(ec)
      override def extractOffset(envelope: Envelope): Offset = provider.extractOffset(envelope)
      override def extractCreationTime(envelope: Envelope): Long = provider.extractCreationTime(envelope)
    }
}

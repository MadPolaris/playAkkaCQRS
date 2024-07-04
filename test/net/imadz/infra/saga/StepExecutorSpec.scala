package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.common.CborSerializable
import net.imadz.infra.saga.SagaParticipant._
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future
import scala.concurrent.duration._

class StepExecutorSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
akka {
       actor {
         serializers {

            proto = "akka.remote.serialization.ProtobufSerializer"
            saga-transaction-step = "net.imadz.infra.saga.SagaTransactionStepSerializer"

         }
         serialization-bindings {
           "com.google.protobuf.Message" = proto
           "net.imadz.infra.saga.SagaTransactionStep" = saga-transaction-step

         }
         allow-java-serialization = on
         warn-about-java-serializer-usage = off
       }

    }
""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
)
  with AnyWordSpecLike
  with BeforeAndAfterEach
  with LogCapturing {
  private def createTestKit(participant: SagaParticipant[String, String], id: String): EventSourcedBehaviorTestKit[Command, Event, State[String, String]] =
    EventSourcedBehaviorTestKit[
      StepExecutor.Command,
      StepExecutor.Event,
      StepExecutor.State[String, String]](
      system,
      StepExecutor(PersistenceId.ofUniqueId(id), participant, 3, 30.seconds, CircuitBreakerSettings(5, 3.seconds, 30.seconds))
    )

  val participant = SuccessfulParticipant
  val eventSourcedTestKit: EventSourcedBehaviorTestKit[Command, Event, State[String, String]] = createTestKit(participant, "test-1")

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }


  "StepExecutor" should {

    "successfully execute Start command" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()

      val reserveFromAccount = SagaTransactionStep[String, String](
        "from-account-reservation", PreparePhase, SuccessfulParticipant
      )
      val result = eventSourcedTestKit.runCommand(Start("trx1", reserveFromAccount, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx1", reserveFromAccount)
      result.state.transactionId shouldBe Some("trx1")
      result.state.step shouldBe Some(reserveFromAccount)
      result.state.status shouldBe Ongoing

      probe.expectMessage(30.seconds, StepCompleted[RetryableOrNotException, String]("trx1", "Prepared"))

    }

  }
}

case object SuccessfulParticipant extends SagaParticipant[String, String] with CborSerializable {
  override def doPrepare(transactionId: String) = Future.successful(Right[String, String]("Prepared"))

  override def doCommit(transactionId: String) = Future.successful(Right[String, String]("Committed"))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, String]("Compensated"))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }
}

class RetryingParticipant(succeedAfter: Int) extends SagaParticipant[String, String] {
  private var attempts = 0

  override def doPrepare(transactionId: String) = {
    attempts += 1
    if (attempts < succeedAfter) Future.failed(RetryableFailure("Retry needed"))
    else Future.successful(Right[String, String]("Success after retry"))
  }

  override def doCommit(transactionId: String) = Future.successful(Right[String, String]("Committed"))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, String]("Compensated"))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }
}


class TimeoutParticipant extends SagaParticipant[String, String] {
  override def doPrepare(transactionId: String) = Future.never // Simulating a long-running operation

  override def doCommit(transactionId: String) = Future.successful(Right[String, String]("Committed"))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, String]("Compensated"))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }}

class AlwaysFailingParticipant extends SagaParticipant[String, String] {
  override def doPrepare(transactionId: String) = Future.failed(RetryableFailure("Always fails"))

  override def doCommit(transactionId: String) = Future.successful(Right[String, String]("Committed"))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, String]("Compensated"))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }}

class NonRetryableFailingParticipant extends SagaParticipant[String, String] {
  override def doPrepare(transactionId: String) = Future.failed(NonRetryableFailure("Critical error"))

  override def doCommit(transactionId: String) = Future.successful(Right[String, String]("Committed"))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, String]("Compensated"))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case _ => NonRetryableFailure("never retry")
  }
}
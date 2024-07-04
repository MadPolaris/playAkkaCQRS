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
      StepExecutor(persistenceId = PersistenceId.ofUniqueId(id),
        participant = participant,
        maxRetries = 2,
        initialRetryDelay = 2.seconds,
        circuitBreakerSettings = CircuitBreakerSettings(5, 30.seconds, 30.seconds))
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

      probe.expectMessage(3.seconds, StepCompleted[RetryableOrNotException, String]("trx1", "Prepared"))

    }

    "successfully execute Start command for PreparePhase" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val eventSourcedTestKit = createTestKit(SuccessfulParticipant, "test-prepare")

      val prepareStep = SagaTransactionStep[String, String](
        "prepare-step", PreparePhase, SuccessfulParticipant
      )
      val result = eventSourcedTestKit.runCommand(Start("trx1", prepareStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx1", prepareStep)
      result.state.transactionId shouldBe Some("trx1")
      result.state.step shouldBe Some(prepareStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(5.seconds, StepCompleted[RetryableOrNotException, String]("trx1", "Prepared"))
    }

    "successfully execute Start command for CommitPhase" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val eventSourcedTestKit = createTestKit(SuccessfulParticipant, "test-commit")

      val commitStep = SagaTransactionStep[String, String](
        "commit-step", CommitPhase, SuccessfulParticipant
      )
      val result = eventSourcedTestKit.runCommand(Start("trx2", commitStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx2", commitStep)
      result.state.transactionId shouldBe Some("trx2")
      result.state.step shouldBe Some(commitStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(5.seconds, StepCompleted[RetryableOrNotException, String]("trx2", "Committed"))
    }

    "successfully execute Start command for CompensatePhase" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val eventSourcedTestKit = createTestKit(SuccessfulParticipant, "test-compensate")

      val compensateStep = SagaTransactionStep[String, String](
        "compensate-step", CompensatePhase, SuccessfulParticipant
      )
      val result = eventSourcedTestKit.runCommand(Start("trx3", compensateStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx3", compensateStep)
      result.state.transactionId shouldBe Some("trx3")
      result.state.step shouldBe Some(compensateStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(5.seconds, StepCompleted[RetryableOrNotException, String]("trx3", "Compensated"))
    }

    "retry on retryable failure" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val retryingParticipant = RetryingParticipant
      val eventSourcedTestKit = createTestKit(retryingParticipant, "test-retry")

      val retryStep = SagaTransactionStep[String, String](
        "retry-step", PreparePhase, retryingParticipant, maxRetries = 5
      )
      val result = eventSourcedTestKit.runCommand(Start("trx4", retryStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx4", retryStep)
      result.state.transactionId shouldBe Some("trx4")
      result.state.step shouldBe Some(retryStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(10.seconds, StepCompleted[RetryableOrNotException, String]("trx4", "Success after retry"))
    }

    "fail after max retries" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val alwaysFailingParticipant = AlwaysFailingParticipant
      val eventSourcedTestKit = createTestKit(alwaysFailingParticipant, "test-max-retries")

      val failingStep = SagaTransactionStep[String, String](
        "failing-step", PreparePhase, alwaysFailingParticipant, maxRetries = 2
      )
      val result = eventSourcedTestKit.runCommand(Start("trx5", failingStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx5", failingStep)
      result.state.transactionId shouldBe Some("trx5")
      result.state.step shouldBe Some(failingStep)
      result.state.status shouldBe Ongoing

      probe.expectMessageType[StepFailed[RetryableOrNotException, String]](10.seconds)
    }

    "fail immediately on non-retryable failure" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val nonRetryableFailingParticipant = NonRetryableFailingParticipant
      val eventSourcedTestKit = createTestKit(nonRetryableFailingParticipant, "test-non-retryable")

      val nonRetryableStep = SagaTransactionStep[String, String](
        "non-retryable-step", PreparePhase, nonRetryableFailingParticipant, maxRetries = 5
      )
      val result = eventSourcedTestKit.runCommand(Start("trx6", nonRetryableStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx6", nonRetryableStep)
      result.state.transactionId shouldBe Some("trx6")
      result.state.step shouldBe Some(nonRetryableStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(5.seconds, StepFailed[RetryableOrNotException, String]("trx6", NonRetryableFailure("Critical error")))
    }

    "timeout on long-running operation" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val timeoutParticipant = TimeoutParticipant
      val eventSourcedTestKit = createTestKit(timeoutParticipant, "test-timeout")

      val timeoutStep = SagaTransactionStep[String, String](
        "timeout-step", PreparePhase, timeoutParticipant, timeoutDuration = 1.seconds, maxRetries = 2
      )
      val result = eventSourcedTestKit.runCommand(Start("trx7", timeoutStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx7", timeoutStep)
      result.state.transactionId shouldBe Some("trx7")
      result.state.step shouldBe Some(timeoutStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(1000.seconds, StepFailed[RetryableOrNotException, String]("trx7", RetryableFailure("timed out")))
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

case object RetryingParticipant extends SagaParticipant[String, String] with CborSerializable {
  private var attempts = 0
  private val succeedAfter = 3

  override def doPrepare(transactionId: String) = {
    attempts += 1
    logger.warn(s"RetryingParticipant is doing prepare $attempts")
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


case object TimeoutParticipant extends SagaParticipant[String, String] {
  override def doPrepare(transactionId: String) = Future.never // Simulating a long-running operation

  override def doCommit(transactionId: String) = Future.successful(Right[String, String]("Committed"))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, String]("Compensated"))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }
}

case object AlwaysFailingParticipant extends SagaParticipant[String, String] {
  override def doPrepare(transactionId: String) = Future.failed(RetryableFailure("Always fails"))

  override def doCommit(transactionId: String) = Future.successful(Right[String, String]("Committed"))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, String]("Compensated"))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }
}

case object NonRetryableFailingParticipant extends SagaParticipant[String, String] {
  override def doPrepare(transactionId: String) = Future.failed(NonRetryableFailure("Critical error"))

  override def doCommit(transactionId: String) = Future.successful(Right[String, String]("Committed"))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, String]("Compensated"))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case _ => NonRetryableFailure("Critical error")
  }
}
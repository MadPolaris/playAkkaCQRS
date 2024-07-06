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
import org.slf4j.LoggerFactory

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
    # In-memory database configuration for unit testing
       database {
         url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
         driver = org.h2.Driver
         connectionPool = disabled
         keepAliveConnection = true
       }
""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
)
  with AnyWordSpecLike
  with BeforeAndAfterEach
  with LogCapturing {

  val logger = LoggerFactory.getLogger(getClass)

  private def createTestKit[E, R](id: String, defaultMaxRetries: Int = 5, initialRetryDelay: FiniteDuration = 2.seconds, circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(5, 30.seconds, 30.seconds)): EventSourcedBehaviorTestKit[Command, Event, State[E, R]] =
    EventSourcedBehaviorTestKit[
      StepExecutor.Command,
      StepExecutor.Event,
      StepExecutor.State[E, R]](
      system,
      StepExecutor(persistenceId = PersistenceId.ofUniqueId(id),
        defaultMaxRetries = defaultMaxRetries,
        initialRetryDelay = initialRetryDelay,
        circuitBreakerSettings = circuitBreakerSettings
      )
    )

  val participant = SuccessfulParticipant
  val eventSourcedTestKit: EventSourcedBehaviorTestKit[Command, Event, State[String, String]] = createTestKit("test-1")

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }


  "StepExecutor" should {

    "successfully execute Start command" in {
      val probe = createTestProbe[StepResult[String, String]]()

      val reserveFromAccount = SagaTransactionStep[String, String](
        "from-account-reservation", PreparePhase, SuccessfulParticipant
      )
      val result = eventSourcedTestKit.runCommand(Start[String, String]("trx1", reserveFromAccount, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx1", reserveFromAccount, probe.ref.path.toSerializationFormat)
      result.state.transactionId shouldBe Some("trx1")
      result.state.step shouldBe Some(reserveFromAccount)
      result.state.status shouldBe Ongoing

      probe.expectMessage(3.seconds, StepCompleted[String, String](
        transactionId = "trx1",
        result = SagaResult("Prepared"),
        state = StepExecutor.State(step = Some(reserveFromAccount), transactionId = Some("trx1"), status = Succeed,
          replyTo = Some(probe.ref.path.toSerializationFormat))
      ))

    }

    "successfully execute Start command for PreparePhase" in {
      val probe = createTestProbe[StepResult[String, String]]()
      val eventSourcedTestKit = createTestKit("test-prepare")

      val prepareStep = SagaTransactionStep[String, String](
        "prepare-step", PreparePhase, SuccessfulParticipant
      )
      val result = eventSourcedTestKit.runCommand(Start("trx1", prepareStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx1", prepareStep, probe.ref.path.toSerializationFormat)
      result.state.transactionId shouldBe Some("trx1")
      result.state.step shouldBe Some(prepareStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(5.seconds, StepCompleted[String, String](
        transactionId = "trx1",
        result = SagaResult("Prepared"),
        state = StepExecutor.State(step = Some(prepareStep), transactionId = Some("trx1"), status = Succeed
          , replyTo = Some(probe.ref.path.toSerializationFormat))
      ))
    }

    "successfully execute Start command for CommitPhase" in {
      val probe = createTestProbe[StepResult[String, String]]()
      val eventSourcedTestKit = createTestKit("test-commit")

      val commitStep = SagaTransactionStep[String, String](
        "commit-step", CommitPhase, SuccessfulParticipant
      )
      val result = eventSourcedTestKit.runCommand(Start("trx2", commitStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx2", commitStep, probe.ref.path.toSerializationFormat)
      result.state.transactionId shouldBe Some("trx2")
      result.state.step shouldBe Some(commitStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(5.seconds, StepCompleted[String, String](
        transactionId = "trx2",
        result = SagaResult("Committed"),
        state = StepExecutor.State(step = Some(commitStep), transactionId = Some("trx2"), status = Succeed
          , replyTo = Some(probe.ref.path.toSerializationFormat))
      ))
    }

    "successfully execute Start command for CompensatePhase" in {
      val probe = createTestProbe[StepResult[String, String]]()
      val eventSourcedTestKit = createTestKit("test-compensate")

      val compensateStep = SagaTransactionStep[String, String](
        "compensate-step", CompensatePhase, SuccessfulParticipant
      )
      val result = eventSourcedTestKit.runCommand(Start("trx3", compensateStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx3", compensateStep, probe.ref.path.toSerializationFormat)
      result.state.transactionId shouldBe Some("trx3")
      result.state.step shouldBe Some(compensateStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(5.seconds, StepCompleted[String, String](
        transactionId = "trx3",
        result = SagaResult("Compensated"),
        state = StepExecutor.State(step = Some(compensateStep), transactionId = Some("trx3"), status = Succeed, replyTo = Some(probe.ref.path.toSerializationFormat))
      ))
    }

    "retry on retryable failure" in {
      val probe = createTestProbe[StepResult[String, String]]()
      val retryingParticipant = RetryingParticipant()
      val eventSourcedTestKit = createTestKit("test-retry")

      val retryStep = SagaTransactionStep[String, String](
        "retry-step", PreparePhase, retryingParticipant, maxRetries = 5
      )
      val result = eventSourcedTestKit.runCommand(Start("trx4", retryStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx4", retryStep, probe.ref.path.toSerializationFormat)
      result.state.transactionId shouldBe Some("trx4")
      result.state.step shouldBe Some(retryStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(10.seconds, StepCompleted[String, String](
        transactionId = "trx4",
        result = SagaResult("Success after retry"),
        state = StepExecutor.State(step = Some(retryStep), transactionId = Some("trx4"), status = Succeed,
          retries = 2,
          lastError = Some(RetryableFailure("Retry needed")),
          replyTo = Some(probe.ref.path.toSerializationFormat)
        )
      ))
    }

    "fail after max retries" in {
      val probe = createTestProbe[StepResult[String, String]]()
      val alwaysFailingParticipant = AlwaysFailingParticipant
      val eventSourcedTestKit = createTestKit("test-max-retries")

      val failingStep = SagaTransactionStep[String, String](
        "failing-step", PreparePhase, alwaysFailingParticipant, maxRetries = 2
      )
      val result = eventSourcedTestKit.runCommand(Start("trx5", failingStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx5", failingStep, probe.ref.path.toSerializationFormat)
      result.state.transactionId shouldBe Some("trx5")
      result.state.step shouldBe Some(failingStep)
      result.state.status shouldBe Ongoing

      probe.expectMessageType[StepFailed[String, String]](10.seconds)
    }

    "fail immediately on non-retryable failure" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val nonRetryableFailingParticipant = NonRetryableFailingParticipant
      val eventSourcedTestKit = createTestKit("test-non-retryable")

      val nonRetryableStep = SagaTransactionStep[RetryableOrNotException, String](
        "non-retryable-step", PreparePhase, nonRetryableFailingParticipant, maxRetries = 5
      )
      val result = eventSourcedTestKit.runCommand(Start("trx6", nonRetryableStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx6", nonRetryableStep, probe.ref.path.toSerializationFormat)
      result.state.transactionId shouldBe Some("trx6")
      result.state.step shouldBe Some(nonRetryableStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(5.seconds, StepFailed[RetryableOrNotException, String](
        transactionId = "trx6",
        error = NonRetryableFailure("Critical error"),
        state = StepExecutor.State(
          step = Some(nonRetryableStep),
          transactionId = Some("trx6"),
          status = Failed,
          lastError = Some(NonRetryableFailure("Critical error")),
          replyTo = Some(probe.ref.path.toSerializationFormat)
        )
      ))
    }

    "timeout on long-running operation" in {
      val probe = createTestProbe[StepResult[RetryableOrNotException, String]]()
      val timeoutParticipant = TimeoutParticipant
      val eventSourcedTestKit = createTestKit("test-timeout")

      val timeoutStep = SagaTransactionStep[RetryableOrNotException, String](
        "timeout-step", PreparePhase, timeoutParticipant, timeoutDuration = 1.seconds, maxRetries = 2
      )
      val result = eventSourcedTestKit.runCommand(Start("trx7", timeoutStep, Some(probe.ref)))

      result.event shouldBe ExecutionStarted("trx7", timeoutStep, probe.ref.path.toSerializationFormat)
      result.state.transactionId shouldBe Some("trx7")
      result.state.step shouldBe Some(timeoutStep)
      result.state.status shouldBe Ongoing

      probe.expectMessage(1000.seconds, StepFailed[RetryableOrNotException, String](
        transactionId = "trx7",
        error = RetryableFailure("timed out"),
        state = StepExecutor.State(step = Some(timeoutStep), transactionId = Some("trx7"), status = Failed,
          lastError = Some(RetryableFailure("timed out")),
          replyTo = Some(probe.ref.path.toSerializationFormat))
      ))
    }

    "recover execution and retry when in Ongoing state" in {
      val probe = createTestProbe[StepResult[String, String]]()
      val retryingParticipant = RetryingParticipant()
      val eventSourcedTestKit = createTestKit("test-recover")

      val recoverStep = SagaTransactionStep[String, String](
        "recover-step", PreparePhase, retryingParticipant, maxRetries = 5, retryWhenRecoveredOngoing = true
      )

      // Simulate a crash after starting execution
      eventSourcedTestKit.runCommand(Start("trx-recover", recoverStep, Some(probe.ref)))
      try {
        Thread.sleep(500L)
      } catch {
        case ignored: Throwable => ()
      }
      eventSourcedTestKit.restart()
      logger.info("restarting executor")

      // The actor should automatically retry the operation
      probe.expectMessage(10.seconds, StepCompleted[String, String](
        transactionId = "trx-recover",
        result = SagaResult("Success after retry"),
        state = StepExecutor.State(step = Some(recoverStep),
          transactionId = Some("trx-recover"),
          status = Succeed,
          retries = 2,
          lastError = Some(RetryableFailure("Retry needed")),
          replyTo = Some(probe.ref.path.toSerializationFormat))
      ))
    }

    "persist events and recover state" in {
      val probe = createTestProbe[StepResult[String, String]]()
      val participant = SuccessfulParticipant
      val eventSourcedTestKit = createTestKit("test-persist")

      val step = SagaTransactionStep[String, String](
        "persist-step", PreparePhase, participant
      )

      eventSourcedTestKit.runCommand(Start("trx-persist", step, Some(probe.ref)))

      // Verify persisted events
      val persistedEvents = eventSourcedTestKit.persistenceTestKit.persistedInStorage("test-persist")
      persistedEvents should contain(ExecutionStarted("trx-persist", step, probe.ref.path.toSerializationFormat))
      persistedEvents should contain(OperationSucceeded(SagaResult("Prepared")))

      // Simulate restart and verify recovered state
      eventSourcedTestKit.restart()
      val recoveredState = eventSourcedTestKit.getState()
      recoveredState.status shouldBe Succeed
      recoveredState.transactionId shouldBe Some("trx-persist")
    }
  }
}

case object SuccessfulParticipant extends SagaParticipant[String, String] with CborSerializable {
  override def doPrepare(transactionId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Prepared")))

  override def doCommit(transactionId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Committed")))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Compensated")))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }
}

case class RetryingParticipant() extends SagaParticipant[String, String] with CborSerializable {
  private var attempts = 0
  private val succeedAfter = 3

  override def doPrepare(transactionId: String) = {
    attempts += 1
    try {
      Thread.sleep(500L)
    } catch {
      case ignored: Throwable => ()
    }
    logger.warn(s"RetryingParticipant is doing prepare $attempts times")
    if (attempts < succeedAfter) Future.failed(RetryableFailure("Retry needed"))
    else Future.successful(Right[String, SagaResult[String]](SagaResult("Success after retry")))
  }

  override def doCommit(transactionId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Committed")))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Compensated")))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }
}


case object TimeoutParticipant extends SagaParticipant[RetryableOrNotException, String] {
  override def doPrepare(transactionId: String) = Future.never // Simulating a long-running operation

  override def doCommit(transactionId: String) = Future.successful(Right[RetryableOrNotException, SagaResult[String]](SagaResult("Committed")))

  override def doCompensate(transactionId: String) = Future.successful(Right[RetryableOrNotException, SagaResult[String]](SagaResult("Compensated")))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }
}

case object AlwaysFailingParticipant extends SagaParticipant[String, String] {
  override def doPrepare(transactionId: String) = {
    logger.warn(s"AlwaysFailingParticipant is failing after 5 seconds")
    try {
      Thread.sleep(1000L)
    } catch {
      case ignored: Throwable => ()
    }
    Future.failed(RetryableFailure("Always fails"))
  }

  override def doCommit(transactionId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Committed")))

  override def doCompensate(transactionId: String) = Future.successful(Right[String, SagaResult[String]](SagaResult("Compensated")))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => NonRetryableFailure("never retry")
  }
}

case object NonRetryableFailingParticipant extends SagaParticipant[RetryableOrNotException, String] {
  override def doPrepare(transactionId: String) = Future.failed(new RuntimeException())

  override def doCommit(transactionId: String) = Future.successful(Right[RetryableOrNotException, SagaResult[String]](SagaResult("Committed")))

  override def doCompensate(transactionId: String) = Future.successful(Right[RetryableOrNotException, SagaResult[String]](SagaResult("Compensated")))


  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case _ => NonRetryableFailure("Critical error")
  }
}

case class CircuitBreakerParticipant() extends SagaParticipant[RetryableOrNotException, String] {
  private var attempts = 0

  override def doPrepare(transactionId: String) = {
    attempts += 1
    Future.failed(RetryableFailure(s"Failure attempt $attempts"))
  }

  override def doCommit(transactionId: String) = Future.successful(Right[RetryableOrNotException, SagaResult[String]](SagaResult("Committed")))

  override def doCompensate(transactionId: String) = Future.successful(Right[RetryableOrNotException, SagaResult[String]](SagaResult("Compensated")))

  override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = {
    case it@RetryableFailure("Retry needed") => it
    case _ => RetryableFailure("Retry needed")
  }
}
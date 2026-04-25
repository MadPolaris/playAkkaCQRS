package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, SagaResult}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult
import net.imadz.infra.saga.StepExecutor.StepResult
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import net.imadz.common.serialization.SerializationExtension
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class SagaTransactionCoordinatorSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka {
      |  extensions = ["net.imadz.common.serialization.SerializationExtension"]
      |  actor {
      |    serializers {
      |      saga-transaction-step-test = "net.imadz.infra.saga.SagaTransactionStepSerializerForTest"
      |    }
      |    serialization-identifiers {
      |      "net.imadz.infra.saga.SagaTransactionStepSerializerForTest" = 9999
      |    }
      |    allow-java-serialization = on
      |    warn-about-java-serializer-usage = off
      |    serialization-bindings {
      |      "net.imadz.infra.saga.SagaTransactionStep" = saga-transaction-step-test
      |      "java.io.Serializable" = java
      |    }
      |  }
      |}
      |# In-memory database configuration for unit testing
      |       database {
      |         url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
      |         driver = org.h2.Driver
      |         connectionPool = disabled
      |         keepAliveConnection = true
      |       }
      |akka.test.single-expect-default = 100s
      |akka.actor.testkit.typed.single-expect-default = 100s
      |akka.actor.testkit.typed.serialize-messages = off
      |akka.actor.testkit.typed.serialize-creators = off
      |akka.actor.testkit.typed.serialization.verify = off
      |akka.persistence.testkit.events.serialize = off
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with BeforeAndAfterEach with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val ext = SerializationExtension(system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem])
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(SuccessfulParticipant))
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(AlwaysFailingParticipant))
  }
      private def createEventSourcedTestKit(stepExecutorCreator: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command]) = {
      EventSourcedBehaviorTestKit[
      SagaTransactionCoordinator.Command,
      SagaTransactionCoordinator.Event,
      SagaTransactionCoordinator.State
      ](
      system,
      SagaTransactionCoordinator(
        PersistenceId.ofUniqueId("test-saga-coordinator"),
        stepExecutorCreator
      )
      )
      }
  private def createSuccessfulStepExecutor[E, R, C](): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo: Option[ActorRef[StepResult[E, R, C]]]) =>
        replyTo.foreach(_ ! StepExecutor.StepCompleted[E, R, C](transactionId, step.stepId, SagaResult.empty[R]()))
        Behaviors.same
    })
  }

  private def createFailingStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo) =>
        replyTo.foreach(_ ! StepExecutor.StepFailed(transactionId, step.stepId, NonRetryableFailure("Test failure")))
        Behaviors.same
    })
  }

  "SagaTransactionCoordinator" should {
    "successfully complete a transaction" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createSuccessfulStepExecutor())
      val transactionId = "test-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step3", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step4", CommitPhase, SuccessfulParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = true,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CommitPhase,
          status = SagaTransactionCoordinator.Completed
        )
      )
    }

    "handle failure during PreparePhase and initiate compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        if (step.phase == PreparePhase) createFailingStepExecutor()
        else createSuccessfulStepExecutor()
      )
      val transactionId = "failed-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("step2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = false,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CompensatePhase,
          status = SagaTransactionCoordinator.Failed
        )
      )
    }

    "handle non-retryable failure during CommitPhase" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        if (step.phase == CommitPhase) createFailingStepExecutor()
        else createSuccessfulStepExecutor()
      )
      val transactionId = "commit-failed-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step2", CommitPhase, AlwaysFailingParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = false,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CompensatePhase,
          status = SagaTransactionCoordinator.Failed
        )
      )
    }
    "handle failure during CommitPhase and successfully compensate" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        if (step.stepId == "commit1") createFailingStepExecutor()
        else createSuccessfulStepExecutor()
      )
      val transactionId = "commit-fail-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("commit2", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("compensate1", CompensatePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("compensate2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = false,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CompensatePhase,
          status = SagaTransactionCoordinator.Failed
        )
      )
    }

    "handle partial failure during CompensatePhase" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        if (step.stepId == "commit2" || step.stepId == "compensate1") createFailingStepExecutor()
        else createSuccessfulStepExecutor()
      )
      val transactionId = "compensate-partial-fail-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit2", CommitPhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("compensate1", CompensatePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("compensate2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = false,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CompensatePhase,
          status = SagaTransactionCoordinator.Failed
        ),
        failReason = "Phase compensate failed with error: StepFailed(compensate-partial-fail-transaction,compensate1,net.imadz.infra.saga.SagaParticipant$NonRetryableFailure: Test failure)"
      )
    }

    "resume execution upon RecoveryCompleted when in InProgress state" in {
      val transactionId = "recover-in-progress-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step2", CommitPhase, SuccessfulParticipant, 2)
      )

      var useHangingExecutor = true
      def hangingStepExecutorCreator(): ActorRef[StepExecutor.Command] = {
        spawn(Behaviors.receiveMessage[StepExecutor.Command] {
          case _ => Behaviors.same
        })
      }

      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => {
        if (useHangingExecutor) hangingStepExecutorCreator()
        else createSuccessfulStepExecutor()
      })

      val prob = createTestProbe[TransactionResult]()
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref)))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps)
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      // Now we change the flag and restart to simulate JVM restart and recovery
      useHangingExecutor = false
      eventSourcedTestKit.restart()

      // After restart, the recovery will trigger RecoveryCompleted, which will resume the transaction
      // and it should complete successfully because it now uses SuccessfulStepExecutor.
      var attempts = 0
      while (eventSourcedTestKit.getState().status != SagaTransactionCoordinator.Completed && attempts < 50) {
        Thread.sleep(100)
        attempts += 1
      }
      
      eventSourcedTestKit.getState().status shouldBe SagaTransactionCoordinator.Completed
    }

  }
}
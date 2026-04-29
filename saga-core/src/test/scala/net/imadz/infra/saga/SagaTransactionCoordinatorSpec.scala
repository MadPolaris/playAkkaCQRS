package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, SagaResult}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult
import net.imadz.common.serialization.SerializationExtension
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

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
      |database {
      |  url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1"
      |  driver = org.h2.Driver
      |  connectionPool = disabled
      |  keepAliveConnection = true
      |}
      |akka.test.single-expect-default = 10s
      |akka.actor.testkit.typed.single-expect-default = 10s
      |akka.actor.testkit.typed.serialize-messages = off
      |akka.actor.testkit.typed.serialize-creators = off
      |akka.actor.testkit.typed.serialization.verify = off
      |akka.persistence.testkit.events.serialize = off
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with BeforeAndAfterEach {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val ext = SerializationExtension(system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem])
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(SuccessfulParticipant))
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(AlwaysFailingParticipant))
  }

  private def createEventSourcedTestKit(stepExecutorCreator: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                                        persistenceId: String = "test-saga-coordinator",
                                        globalTimeout: FiniteDuration = 5.seconds) = {
    EventSourcedBehaviorTestKit[
      SagaTransactionCoordinator.Command,
      SagaTransactionCoordinator.Event,
      SagaTransactionCoordinator.State
    ](
      system,
      SagaTransactionCoordinator(
        PersistenceId.ofUniqueId(persistenceId),
        stepExecutorCreator,
        globalTimeout = globalTimeout
      )(system.executionContext, 5.seconds) // Reduced ask timeout for tests
    )
  }

  private def createSuccessfulStepExecutor[E, R, C](): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo, _) =>
        replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
        Behaviors.stopped
      case qs: StepExecutor.QueryStatus[E, R, C] =>
        qs.replyTo ! StepExecutor.State(status = StepExecutor.Succeed, result = Some(SagaResult.empty[R]()))
        Behaviors.same
      case _ => Behaviors.same
    })
  }

  private def createFailingStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo, _) =>
        replyTo.foreach(_ ! StepExecutor.StepFailed(transactionId, step.stepId, NonRetryableFailure("Test failure")))
        Behaviors.stopped
      case qs: StepExecutor.QueryStatus[_, _, _] =>
        qs.replyTo ! StepExecutor.State(status = StepExecutor.Failed, lastError = Some(NonRetryableFailure("Test failure")))
        Behaviors.same
      case _ => Behaviors.same
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
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps, "test-trace-id")
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = true,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CommitPhase,
          status = SagaTransactionCoordinator.Completed,
          traceId = "test-trace-id"
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
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps, "test-trace-id")
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = false,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CompensatePhase,
          status = SagaTransactionCoordinator.Failed,
          traceId = "test-trace-id"
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
      val result = eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      result.event shouldBe SagaTransactionCoordinator.TransactionStarted(transactionId, steps, "test-trace-id")
      result.state.status shouldBe SagaTransactionCoordinator.InProgress

      val expected = prob.receiveMessage(10.seconds)

      expected shouldBe TransactionResult(
        successful = false,
        SagaTransactionCoordinator.State(
          transactionId = Some(transactionId),
          steps = steps,
          currentPhase = CompensatePhase,
          status = SagaTransactionCoordinator.Suspended,
          traceId = "test-trace-id"
        ),
        failReason = "Phase compensate failed with error: StepFailed(compensate-partial-fail-transaction,compensate1,net.imadz.infra.saga.SagaParticipant$NonRetryableFailure: Test failure)"
      )
    }

    "should resume execution upon RecoveryCompleted when in InProgress state" in {
      val transactionId = "recover-in-progress-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2)
      )
      
      // Use a custom executor that hangs initially
      var shouldHanging = true
      def hangingStepExecutorCreator(): ActorRef[StepExecutor.Command] = {
        spawn(Behaviors.receiveMessage[StepExecutor.Command] {
          case StepExecutor.Start(transactionId, step, replyTo, _) =>
            if (shouldHanging) {
               Behaviors.same // Hangs
            } else {
               replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
               Behaviors.stopped
            }
          case qs: StepExecutor.QueryStatus[_, _, _] =>
            if (shouldHanging) qs.replyTo ! StepExecutor.State(status = StepExecutor.Ongoing)
            else qs.replyTo ! StepExecutor.State(status = StepExecutor.Succeed, result = Some(SagaResult.empty()))
            Behaviors.same
          case _ => Behaviors.same
        })
      }

      // Increase global timeout for recovery test
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => hangingStepExecutorCreator(), persistenceId = "test-saga-recover", globalTimeout = 20.seconds)

      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))
      
      // 2. Restart/Start the coordinator to trigger recovery
      shouldHanging = false
      eventSourcedTestKit.restart()

      // 3. Verify it resumed and eventually completed by checking persisted events
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionStarted]("test-saga-recover")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseSucceeded]("test-saga-recover", 20.seconds)
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseSucceeded]("test-saga-recover", 20.seconds)
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionCompleted]("test-saga-recover", 20.seconds)
    }

    "should handle TransactionTimeout and fail the transaction" in {
      val transactionId = "timeout-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2)
      )

      val prob = createTestProbe[TransactionResult]()
      // Use a custom coordinator with a very short timeout and responsive StepExecutor
      val eventSourcedTestKit = createEventSourcedTestKit(
            (_, _) => spawn(Behaviors.receiveMessage[StepExecutor.Command] {
               case StepExecutor.QueryStatus(replyTo) => 
                  replyTo ! StepExecutor.State(status = StepExecutor.Ongoing)
                  Behaviors.same
               case _ => Behaviors.same
            }),
            persistenceId = "test-saga-timeout-test",
            globalTimeout = 200.millis
      )

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      // 4. Verify it ended up in Failed via events since global timeout doesn't have replyTo
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionStarted]("test-saga-timeout-test")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseFailed]("test-saga-timeout-test", 10.seconds)
      // Transition to CompensatePhase happens, but no compensation steps are defined for this test so it might stop or define them
      // Actually successful steps were used, but we mocked StepExecutor to return Ongoing.
      // So it will persist PhaseFailed(Prepare) -> currentPhase = Compensate -> executePhase(Compensate)
      // Since there are no steps for Compensate, handlePhaseCompletion(Compensate, List.empty) is called.
      // Which persists PhaseSucceeded(Compensate) and TransactionFailed.
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseSucceeded]("test-saga-timeout-test", 10.seconds)
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionFailed]("test-saga-timeout-test", 10.seconds)
    }

    "handle ask timeout by querying status and eventually completing" in {
      val transactionId = "timeout-query-status-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2)
      )

      def slowStepExecutorCreator(): ActorRef[StepExecutor.Command] = {
        spawn(Behaviors.receiveMessage[StepExecutor.Command] {
          case StepExecutor.Start(transactionId, step, replyTo, traceId) =>
            // Do not reply immediately to simulate ask timeout
            Behaviors.same
          case qs: StepExecutor.QueryStatus[_, _, _] =>
            // Reply with Succeed to simulate it finished later
            qs.replyTo.asInstanceOf[ActorRef[StepExecutor.State[Any, Any, Any]]] ! StepExecutor.State(
              status = StepExecutor.Succeed,
              result = Some(SagaResult.empty[Any]())
            )
            Behaviors.same
          case msg => 
            Behaviors.same
        })
      }

      // Increase global timeout to not interfere with ask timeout
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => slowStepExecutorCreator(), persistenceId = "test-saga-ask-timeout", globalTimeout = 20.seconds)

      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      // 3. Verify it resumed and eventually completed by checking persisted events
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionStarted]("test-saga-ask-timeout")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseSucceeded]("test-saga-ask-timeout", 20.seconds)
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseSucceeded]("test-saga-ask-timeout", 20.seconds)
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionCompleted]("test-saga-ask-timeout", 20.seconds)
    }

  }
}

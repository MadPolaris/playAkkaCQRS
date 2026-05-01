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
                                        globalTimeout: FiniteDuration = 30.seconds) = {
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
      )(system.executionContext)
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
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createSuccessfulStepExecutor(), persistenceId = "test-saga-success")
      val transactionId = "test-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step3", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("step4", CommitPhase, SuccessfulParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      val result = prob.receiveMessage(15.seconds)

      result.successful shouldBe true
      result.state.status shouldBe SagaTransactionCoordinator.Completed
      result.state.currentPhase shouldBe CommitPhase
    }

    "handle failure during PreparePhase and initiate compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        if (step.phase == PreparePhase) createFailingStepExecutor()
        else createSuccessfulStepExecutor()
      , persistenceId = "failed-transaction")
      val transactionId = "failed-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("step2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      val result = prob.receiveMessage(15.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.state.currentPhase shouldBe CompensatePhase
    }

    "handle partial failure during CompensatePhase" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        if (step.stepId == "commit2" || step.stepId == "compensate1") createFailingStepExecutor()
        else createSuccessfulStepExecutor()
      , persistenceId = "compensate-partial-fail-transaction")
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
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      val result = prob.receiveMessage(15.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Suspended
      result.state.currentPhase shouldBe CompensatePhase
      result.failReason should include ("Phase compensate failed with error: Test failure")
    }

    "should resume execution upon RecoveryCompleted when in InProgress state" in {
      val transactionId = "recover-in-progress-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2)
      )
      
      var shouldHanging = true
      def hangingStepExecutorCreator(): ActorRef[StepExecutor.Command] = {
        spawn(Behaviors.receiveMessage[StepExecutor.Command] {
          case StepExecutor.Start(transactionId, step, replyTo, _) =>
            if (shouldHanging) {
               Behaviors.same
            } else {
               replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
               Behaviors.stopped
            }
          case _ => Behaviors.same
        })
      }

      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => hangingStepExecutorCreator(), persistenceId = "test-saga-recover", globalTimeout = 20.seconds)

      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))
      
      shouldHanging = false
      eventSourcedTestKit.restart()

      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionStarted]("test-saga-recover")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.StepGroupStarted]("test-saga-recover")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.StepGroupStarted]("test-saga-recover")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.StepResultReceived]("test-saga-recover")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseSucceeded]("test-saga-recover")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseSucceeded]("test-saga-recover")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionCompleted]("test-saga-recover")
    }

    "should handle TransactionTimeout and fail the transaction" in {
      val transactionId = "timeout-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2)
      )

      val prob = createTestProbe[TransactionResult]()
      val eventSourcedTestKit = createEventSourcedTestKit(
            (_, _) => spawn(Behaviors.receiveMessage[StepExecutor.Command] {
               case _ => Behaviors.same
            }),
            persistenceId = "test-saga-timeout-test",
            globalTimeout = 200.millis
      )

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionStarted]("test-saga-timeout-test")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.StepGroupStarted]("test-saga-timeout-test")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseFailed]("test-saga-timeout-test", 10.seconds)
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.PhaseSucceeded]("test-saga-timeout-test", 10.seconds)
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionFailed]("test-saga-timeout-test", 10.seconds)
    }

    "handle eventual completion of steps" in {
      val transactionId = "eventual-completion-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2)
      )

      def delayedStepExecutorCreator(): ActorRef[StepExecutor.Command] = {
        spawn(Behaviors.receiveMessage[StepExecutor.Command] {
          case StepExecutor.Start(transactionId, step, replyTo, traceId) =>
            implicit val ec = system.executionContext
            system.scheduler.scheduleOnce(1.second, new Runnable {
              override def run(): Unit = replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
            })
            Behaviors.same
          case _ => Behaviors.same
        })
      }

      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => delayedStepExecutorCreator(), persistenceId = "test-saga-eventual", globalTimeout = 20.seconds)

      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))

      val result = prob.receiveMessage(15.seconds)
      result.successful shouldBe true
    }

    "ignore StartTransaction if already InProgress" in {
      val transactionId = "already-started"
      val steps = List(SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2))
      // Use hanging executor to keep it InProgress
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => 
        spawn(Behaviors.receiveMessage[StepExecutor.Command] { case _ => Behaviors.same }), 
        persistenceId = "test-saga-already-started")
      
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, None, "test-trace-id"))
      
      eventually(timeout(10.seconds)) {
        eventSourcedTestKit.getState().status shouldBe SagaTransactionCoordinator.InProgress
      }
      
      // This should be ignored (Effect.none)
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, None, "test-trace-id"))
      
      eventSourcedTestKit.getState().status shouldBe SagaTransactionCoordinator.InProgress
    }

    "handle TransactionPaused and ProceedNext" in {
      val transactionId = "paused-transaction"
      val steps = List(SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2))
      // Increase globalTimeout to 100 seconds to avoid timeout during the test
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createSuccessfulStepExecutor(), persistenceId = "test-saga-paused", globalTimeout = 100.seconds)
      
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, None, "test-trace-id", singleStep = true))
      
      eventually(timeout(10.seconds)) {
        eventSourcedTestKit.getState().status shouldBe SagaTransactionCoordinator.InProgress
        eventSourcedTestKit.getState().isPaused shouldBe true
      }
      
      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.ProceedNext(Some(prob.ref)))
      
      val result = prob.receiveMessage(30.seconds)
      result.successful shouldBe true
      result.state.status shouldBe SagaTransactionCoordinator.Completed
    }

    "handle ResolveSuspended when Suspended" in {
      val transactionId = "suspended-transaction"
      val steps = List(
        SagaTransactionStep("step1", PreparePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("step2", CompensatePhase, AlwaysFailingParticipant, 2)
      )
      // Step1 fails in PreparePhase -> triggers Step2 in CompensatePhase -> Step2 fails -> Suspended
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createFailingStepExecutor()
      , persistenceId = "test-saga-suspended")
      
      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(prob.ref), "test-trace-id"))
      
      // Wait for it to become Suspended
      eventually(timeout(15.seconds)) {
        eventSourcedTestKit.getState().status shouldBe SagaTransactionCoordinator.Suspended
      }
      
      // Now try to resolve it
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.ResolveSuspended(Some(prob.ref)))
      
      // It should eventually become Suspended again (since it fails again)
      eventually(timeout(15.seconds)) {
        eventSourcedTestKit.getState().status shouldBe SagaTransactionCoordinator.Suspended
      }
    }

    "handle RetryCurrentPhase" in {
      val transactionId = "retry-phase-transaction"
      val steps = List(SagaTransactionStep("step1", PreparePhase, SuccessfulParticipant, 2))
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => 
            spawn(Behaviors.receiveMessage[StepExecutor.Command] {
               case _ => Behaviors.same // Hang to keep it InProgress
            }), persistenceId = "test-saga-retry-phase")
      
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, None, "test-trace-id"))
      
      eventually(timeout(10.seconds)) {
        eventSourcedTestKit.getState().status shouldBe SagaTransactionCoordinator.InProgress
      }
      
      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.RetryCurrentPhase(None))
      
      eventSourcedTestKit.getState().status shouldBe SagaTransactionCoordinator.InProgress
    }


  }
}

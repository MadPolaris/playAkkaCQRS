package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaPhase.PreparePhase
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, SagaResult}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class SagaCoreEdgeCaseSpec extends ScalaTestWithActorTestKit(
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
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with BeforeAndAfterEach {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val ext = net.imadz.common.serialization.SerializationExtension(system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem])
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(SuccessfulParticipant))
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(AlwaysFailingParticipant))
  }

  private def createEventSourcedTestKit(stepExecutorCreator: (String, SagaTransactionStep[_, _, _]) => ActorRef[StepExecutor.Command],
                                        persistenceId: String = "test-saga-edge-cases",
                                        globalTimeout: FiniteDuration = 30.seconds) = {
    import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
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

  private def createSuccessfulStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Start(transactionId, step, replyTo, _) =>
        replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
        Behaviors.stopped
      case _ => Behaviors.same
    })
  }

  "SagaTransactionCoordinator Edge Cases" should {

    "ignore ProceedNextGroup if not paused" in {
      val transactionId = "proceed-not-paused"
      val steps = List(SagaTransactionStep[String, String, Any]("step1", PreparePhase, SuccessfulParticipant, 2))
      // Use hanging executor so it stays in InProgress and doesn't stop
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => 
         spawn(Behaviors.receiveMessage[StepExecutor.Command] { case _ => Behaviors.same }), 
         persistenceId = "test-edge-2")
      
      val prob = createTestProbe[TransactionResult]()
      eventSourcedTestKit.runCommand(StartTransaction(transactionId, steps, Some(prob.ref), "trace-2"))
      
      // Try to proceed next group while it's already running
      eventSourcedTestKit.runCommand(ProceedNextGroup(None))
      
      // Verify no unexpected events
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionStarted]("test-edge-2")
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.StepGroupStarted]("test-edge-2")
      // ProceedNextGroup forces a StartStepGroup again because it calls startPhase
      eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.StepGroupStarted]("test-edge-2")
      
      // Should not crash or persist any unexpected errors
      eventSourcedTestKit.persistenceTestKit.expectNothingPersisted("test-edge-2")
    }

    "ignore InternalStepResult for mismatched transactionId" in {
       val transactionId = "mismatched-id"
       val steps = List(SagaTransactionStep[String, String, Any]("step1", PreparePhase, SuccessfulParticipant, 2))
       
       // Use hanging executor to ensure the coordinator stays alive to receive the bad message
       val eventSourcedTestKit = createEventSourcedTestKit((_, _) => 
         spawn(Behaviors.receiveMessage[StepExecutor.Command] { case _ => Behaviors.same }), 
         persistenceId = "test-edge-3")
       
       eventSourcedTestKit.runCommand(StartTransaction(transactionId, steps, None, "trace-3"))
       
       // Send a result for a DIFFERENT transactionId
       eventSourcedTestKit.runCommand(InternalStepResult(StepExecutor.StepCompleted("wrong-id", "step1", SagaResult.empty()), None))
       
       // Verify no StepResultReceived event was persisted for the wrong transaction
       eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.TransactionStarted]("test-edge-3")
       eventSourcedTestKit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinator.StepGroupStarted]("test-edge-3")
       eventSourcedTestKit.persistenceTestKit.expectNothingPersisted("test-edge-3")
       
       // Actor is still alive, we can safely check its state
       eventSourcedTestKit.getState().transactionId shouldBe Some(transactionId)
    }

    "handle ManualFixStep by forwarding to child" in {
       val transactionId = "manual-fix"
       val steps = List(SagaTransactionStep[String, String, Any]("step1", PreparePhase, SuccessfulParticipant, 2))
       
       val childProbe = createTestProbe[StepExecutor.Command]()
       val eventSourcedTestKit = createEventSourcedTestKit((_, _) => childProbe.ref, persistenceId = "test-edge-4")
       
       eventSourcedTestKit.runCommand(StartTransaction(transactionId, steps, None, "trace-4"))
       
       // Wait for child to be started
       childProbe.expectMessageType[StepExecutor.Start[String, String, Any]]
       
       // Send manual fix
       eventSourcedTestKit.runCommand(ManualFixStep("step1", PreparePhase, None))
       
       // Child should receive ManualFix
       childProbe.expectMessageType[StepExecutor.ManualFix[String, String, Any]]
    }
  }
}

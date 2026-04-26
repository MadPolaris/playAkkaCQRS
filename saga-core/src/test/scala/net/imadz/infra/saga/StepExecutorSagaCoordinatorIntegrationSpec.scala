package net.imadz.infra.saga

import akka.actor.ExtendedActorSystem
import akka.actor.testkit.typed.scaladsl.{LogCapturing, ScalaTestWithActorTestKit}
import akka.actor.typed.ActorRef
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.RetryableFailure
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator.TransactionResult
import net.imadz.infra.saga.StepExecutor.CircuitBreakerSettings
import net.imadz.common.serialization.SerializationExtension
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class StepExecutorSagaCoordinatorIntegrationSpec extends ScalaTestWithActorTestKit(
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
    ext.registerStrategy(new TestParticipantSerializerStrategy(classOf[RetryingParticipant].getName, classOf[RetryingParticipant], () => new RetryingParticipant()))
    ext.registerStrategy(new TestParticipantSerializerStrategy(classOf[TimeoutParticipant].getName, classOf[TimeoutParticipant], () => new TimeoutParticipant()))
    ext.registerStrategy(new TestParticipantSerializerStrategy(classOf[CircuitBreakerParticipant].getName, classOf[CircuitBreakerParticipant], () => new CircuitBreakerParticipant()))
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

  "StepExecutor and SagaTransactionCoordinator Integration" should {

    "successfully complete a transaction with multiple steps across different phases" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, _) => createStepExecutor(system.classicSystem.asInstanceOf[ExtendedActorSystem]))
      val transactionId = "multi-phase-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit2", CommitPhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe true
      result.state.status shouldBe SagaTransactionCoordinator.Completed
      result.state.currentPhase shouldBe CommitPhase
    }

    "handle failure in Prepare phase and initiate compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor(system.classicSystem.asInstanceOf[ExtendedActorSystem])
      )
      val transactionId = "prepare-fail-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("compensate1", CompensatePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("compensate2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.state.currentPhase shouldBe CompensatePhase
    }

    "handle failure in Commit phase and compensate all steps" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor(system.classicSystem.asInstanceOf[ExtendedActorSystem])
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
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.state.currentPhase shouldBe CompensatePhase
    }

    "retry a step with temporary failure" in {
      val retryingParticipant = new RetryingParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor(system.classicSystem.asInstanceOf[ExtendedActorSystem])
      )
      val transactionId = "retry-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, retryingParticipant, 5),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      println(result)

      result.successful shouldBe true
      result.state.status shouldBe SagaTransactionCoordinator.Completed
    }

    // Add more test cases here...

    "handle circuit breaker behavior" in {
      val circuitBreakerParticipant = new CircuitBreakerParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor(system.classicSystem.asInstanceOf[ExtendedActorSystem])
      )
      val transactionId = "circuit-breaker-transaction"
      val steps = List(
        SagaTransactionStep("circuit-breaker-step", PreparePhase, circuitBreakerParticipant, 10, 1.seconds))
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(15.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
    }


    "handle timeout in a step" in {
      val timeoutParticipant = new TimeoutParticipant()
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) =>
        createStepExecutor(system.classicSystem.asInstanceOf[ExtendedActorSystem])
      )
      val transactionId = "timeout-transaction"
      val steps = List(
        SagaTransactionStep("timeout-step", PreparePhase, timeoutParticipant, 2, timeoutDuration = 500.millis),
        SagaTransactionStep("compensate-step", CompensatePhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Failed
      result.state.currentPhase shouldBe CompensatePhase
    }

    "handle partial compensation" in {
      val eventSourcedTestKit = createEventSourcedTestKit((_, step) => createStepExecutor(system.classicSystem.asInstanceOf[ExtendedActorSystem]))
      val transactionId = "partial-compensate-transaction"
      val steps = List(
        SagaTransactionStep("prepare1", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("prepare2", PreparePhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit1", CommitPhase, SuccessfulParticipant, 2),
        SagaTransactionStep("commit2", CommitPhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("compensate1", CompensatePhase, AlwaysFailingParticipant, 2),
        SagaTransactionStep("compensate2", CompensatePhase, SuccessfulParticipant, 2)
      )
      val probe = createTestProbe[TransactionResult]()

      eventSourcedTestKit.runCommand(SagaTransactionCoordinator.StartTransaction(transactionId, steps, Some(probe.ref)))

      val result = probe.receiveMessage(10.seconds)

      result.successful shouldBe false
      result.state.status shouldBe SagaTransactionCoordinator.Suspended
      result.state.currentPhase shouldBe CompensatePhase
    }
  }

  private def createStepExecutor(extendedActorSystem: ExtendedActorSystem, circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(5, 30.seconds, 30.seconds)) = {
    spawn(StepExecutor[Any, Any, Any](
      PersistenceId.ofUniqueId(s"step-executor-${java.util.UUID.randomUUID()}"),
      defaultMaxRetries = 5,
      initialRetryDelay = 100.millis,
      circuitBreakerSettings = circuitBreakerSettings,
      context = 0,
      extendedSystem = extendedActorSystem
    ))
  }

}

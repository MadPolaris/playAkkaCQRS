package net.imadz.infra.saga

import akka.actor.ExtendedActorSystem
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, SagaResult}
import net.imadz.infra.saga.SagaPhase.PreparePhase
import net.imadz.infra.saga.StepExecutor._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class StepExecutorSpec extends ScalaTestWithActorTestKit(
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
) with AnyWordSpecLike with BeforeAndAfterEach with Eventually {

  private val circuitBreakerSettings = CircuitBreakerSettings(maxFailures = 3, callTimeout = 10.seconds, resetTimeout = 1.second)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val ext = net.imadz.common.serialization.SerializationExtension(system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem])
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(SuccessfulParticipant))
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(AlwaysFailingParticipant))
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(NonRetryableFailingParticipant))
    ext.registerStrategy(TestParticipantSerializerStrategy.forObject(TimeoutParticipant()))
  }

  private def stepExecutorBehavior(persistenceId: String) = {
      StepExecutor[String, String, Any](
        persistenceId = PersistenceId.ofUniqueId(persistenceId),
        context = 0,
        defaultMaxRetries = 5,
        initialRetryDelay = 100.millis,
        circuitBreakerSettings = circuitBreakerSettings,
        extendedSystem = system.classicSystem.asInstanceOf[ExtendedActorSystem]
      )
  }

  "StepExecutor" should {

    "successfully execute an operation" in {
      val persistenceId = "test-step-executor-1"
      val ref = spawn(stepExecutorBehavior(persistenceId))
      val probe = createTestProbe[StepResult[String, String, Any]]()

      val reserveFromAccount = SagaTransactionStep[String, String, Any](
        "step1",
        PreparePhase,
        SuccessfulParticipant,
        2,
        traceId = "test-trace-id"
      )

      ref ! Start[String, String, Any]("trx1", reserveFromAccount, Some(probe.ref), "test-trace-id")

      // Wait for completion message
      probe.expectMessage(20.seconds, StepCompleted[String, String, Any](
        transactionId = "trx1",
        stepId = reserveFromAccount.stepId,
        result = SagaResult("Prepared")
      ))
    }

    "handle non-retryable failure" in {
      val persistenceId = "test-step-executor-non-retryable"
      val ref = spawn(stepExecutorBehavior(persistenceId))
      val probe = createTestProbe[StepResult[String, String, Any]]()

      val nonRetryableStep = SagaTransactionStep[String, String, Any](
        "step1",
        PreparePhase,
        AlwaysFailingParticipant,
        2,
        traceId = "test-trace-id"
      )

      ref ! Start("trx6", nonRetryableStep, Some(probe.ref), "test-trace-id")

      // Wait for completion with failure
      val failedResult = probe.receiveMessage(20.seconds).asInstanceOf[StepFailed[String, String, Any]]
      failedResult.transactionId shouldBe "trx6"
    }

    "handle query status while ongoing" in {
      val persistenceId = "test-step-executor-query"
      val ref = spawn(stepExecutorBehavior(persistenceId))
      
      val step = SagaTransactionStep[String, String, Any]("step1", PreparePhase, TimeoutParticipant(), 2, traceId = "test-trace-id")
      val probe = createTestProbe[StepResult[String, String, Any]]()
      
      ref ! Start("trx-persist", step, Some(probe.ref), "test-trace-id")

      // Verify QueryStatus while it's ongoing
      val queryProbe = createTestProbe[State[String, String, Any]]()
      ref ! QueryStatus(queryProbe.ref)
      val state = queryProbe.receiveMessage(5.seconds)
      state.transactionId shouldBe Some("trx-persist")
      state.traceId shouldBe Some("test-trace-id")
      state.status shouldBe Ongoing
    }

    "idempotency: return cached result for already succeeded step" in {
      val persistenceId = "test-step-executor-idempotency"
      val ref = spawn(stepExecutorBehavior(persistenceId))
      val probe = createTestProbe[StepResult[String, String, Any]]()

      val step = SagaTransactionStep[String, String, Any]("step1", PreparePhase, SuccessfulParticipant, 2, traceId = "test-trace-id")
      
      // 1. First execution
      ref ! Start("trx-idempotent", step, Some(probe.ref), "test-trace-id")
      probe.expectMessage(20.seconds, StepCompleted[String, String, Any]("trx-idempotent", "step1", SagaResult("Prepared")))
      
      // 2. Start a new instance to simulate a new sharded incarnation (since the old one stopped)
      val ref2 = spawn(stepExecutorBehavior(persistenceId))

      // 3. Second execution with same trxId/step
      val probe2 = createTestProbe[StepResult[String, String, Any]]()
      ref2 ! Start("trx-idempotent", step, Some(probe2.ref), "test-trace-id")
      
      // Should return cached result without re-executing
      probe2.expectMessage(20.seconds, StepCompleted[String, String, Any]("trx-idempotent", "step1", SagaResult("Prepared")))
    }
  }
}

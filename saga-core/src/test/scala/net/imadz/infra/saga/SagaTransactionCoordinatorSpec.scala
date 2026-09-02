package net.imadz.infra.saga

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.{NonRetryableFailure, SagaResult}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator.{SagaStartReply, Started, TransactionResult}
import net.imadz.infra.saga.acceptance.TestSagaSupport
import net.imadz.infra.saga.dsl.SagaDefinition
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class SagaTransactionCoordinatorSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka {
      |  actor {
      |    allow-java-serialization = on
      |    warn-about-java-serializer-usage = off
      |  }
      |akka.test.single-expect-default = 10s
      |akka.actor.testkit.typed.single-expect-default = 10s
      |akka.actor.testkit.typed.serialize-messages = off
      |akka.actor.testkit.typed.serialize-creators = off
      |akka.actor.testkit.typed.serialization.verify = off
      |akka.persistence.testkit.events.serialize = off
      |}
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with BeforeAndAfterEach {

  import TestSagaSupport._

  private val ec = system.executionContext
  private implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

  private def createEventSourcedTestKit(stepExecutorCreator: String => ActorRef[StepExecutor.Command],
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
      )(ec, 5.seconds)
    )
  }

  private def createSuccessfulStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Attach(transactionId, step, replyTo, _) =>
        replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
        Behaviors.stopped
      case qs: StepExecutor.QueryStatus[_, _, _] =>
        qs.replyTo.asInstanceOf[ActorRef[StepExecutor.State[Any, Any, Any]]] !
          StepExecutor.State[Any, Any, Any](status = StepExecutor.Succeed, result = Some(SagaResult.empty[Any]()))
        Behaviors.same
      case _ => Behaviors.same
    })
  }

  private def createFailingStepExecutor(): ActorRef[StepExecutor.Command] = {
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Attach(transactionId, step, replyTo, _) =>
        replyTo.foreach(_ ! StepExecutor.StepFailed(transactionId, step.stepId, NonRetryableFailure("Test failure")))
        Behaviors.stopped
      case qs: StepExecutor.QueryStatus[_, _, _] =>
        qs.replyTo ! StepExecutor.State(status = StepExecutor.Failed, lastError = Some(NonRetryableFailure("Test failure")))
        Behaviors.same
      case _ => Behaviors.same
    })
  }

  private def start(kit: EventSourcedBehaviorTestKit[SagaTransactionCoordinator.Command, SagaTransactionCoordinator.Event, SagaTransactionCoordinator.State],
                    definition: SagaDefinition[String, Any, TransferArgs],
                    transactionId: String,
                    args: TransferArgs = TransferArgs("a", "b", 10),
                    traceId: String = "test-trace-id"): akka.actor.testkit.typed.scaladsl.TestProbe[TransactionResult] = {
    val startProbe = createTestProbe[SagaStartReply]()
    val completionProbe = createTestProbe[TransactionResult]()
    kit.runCommand(SagaTransactionCoordinator.StartSaga(transactionId, definition.name, definition.version,
      definition.argsCodec.encode(args), traceId, singleStep = false, Some(startProbe.ref), Some(completionProbe.ref)))
    startProbe.expectMessage(Started)
    completionProbe
  }

  "SagaTransactionCoordinator" should {
    "successfully complete a transaction" in {
      val definition = registerDefinition("spec-ok", steps = Seq(logicalStep("s1", AlwaysOkRawParticipant), logicalStep("s2", AlwaysOkRawParticipant)))
      val eventSourcedTestKit = createEventSourcedTestKit(_ => createSuccessfulStepExecutor(), persistenceId = "spec-ok-tx")
      val transactionId = "test-transaction"
      val completionProbe = start(eventSourcedTestKit, definition, transactionId)

      val result = completionProbe.receiveMessage(10.seconds)
      result.successful shouldBe true
      result.snapshot.status shouldBe SagaTransactionCoordinator.Completed.toString
      result.snapshot.transactionId shouldBe transactionId
      result.snapshot.steps.map(_.stepId).distinct should contain theSameElementsAs List("s1", "s2")
    }

    "handle failure during PreparePhase and initiate compensation" in {
      val definition = registerDefinition("spec-pfail", steps = Seq(logicalStep("s1", AlwaysOkRawParticipant)))
      val eventSourcedTestKit = createEventSourcedTestKit(
        name => if (name.endsWith("prepare")) createFailingStepExecutor() else createSuccessfulStepExecutor(),
        persistenceId = "spec-pfail-tx")
      val transactionId = "failed-transaction"
      val completionProbe = start(eventSourcedTestKit, definition, transactionId)

      val result = completionProbe.receiveMessage(10.seconds)
      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Failed.toString
      result.snapshot.currentPhase shouldBe CompensatePhase.toString
    }

    "handle partial failure during CompensatePhase" in {
      val definition = registerDefinition("spec-partial", steps = Seq(
        logicalStep("compensate1", AlwaysOkRawParticipant), logicalStep("compensate2", AlwaysOkRawParticipant)))
      val eventSourcedTestKit = createEventSourcedTestKit(
        name => if (name.endsWith("commit") || name.endsWith("compensate")) createFailingStepExecutor() else createSuccessfulStepExecutor(),
        persistenceId = "spec-partial-tx")
      val transactionId = "compensate-partial-fail-transaction"
      val completionProbe = start(eventSourcedTestKit, definition, transactionId)

      val result = completionProbe.receiveMessage(10.seconds)
      result.successful shouldBe false
      result.snapshot.status shouldBe SagaTransactionCoordinator.Suspended.toString
      result.failReason should include("Phase compensate failed with error")
    }

    "should resume execution upon RecoveryCompleted when in InProgress state" in {
      val definition = registerDefinition("spec-recover", steps = Seq(logicalStep("s1", AlwaysOkRawParticipant)))
      val transactionId = "recover-in-progress-transaction"

      var shouldHang = true
      def hangingStepExecutorCreator(): ActorRef[StepExecutor.Command] = {
        spawn(Behaviors.receiveMessage[StepExecutor.Command] {
          case StepExecutor.Attach(transactionId, step, replyTo, _) =>
            if (shouldHang) {
               Behaviors.same // Hangs
            } else {
               replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
               Behaviors.stopped
            }
          case qs: StepExecutor.QueryStatus[_, _, _] =>
            val replyAs = qs.replyTo.asInstanceOf[ActorRef[StepExecutor.State[Any, Any, Any]]]
            if (shouldHang) replyAs ! StepExecutor.State[Any, Any, Any](status = StepExecutor.Ongoing)
            else replyAs ! StepExecutor.State[Any, Any, Any](status = StepExecutor.Succeed, result = Some(SagaResult.empty[Any]()))
            Behaviors.same
          case _ => Behaviors.same
        })
      }

      val eventSourcedTestKit = createEventSourcedTestKit(_ => hangingStepExecutorCreator(), persistenceId = "test-saga-recover", globalTimeout = 20.seconds)
      start(eventSourcedTestKit, definition, transactionId)

      // 2. Restart/Start the coordinator to trigger recovery
      shouldHang = false
      eventSourcedTestKit.restart()

      // 3. Verify it resumed and eventually completed by checking persisted journal POs
      nextJournalEvent(eventSourcedTestKit, "test-saga-recover") { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.Started(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-recover", 20.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-recover", 20.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-recover", 20.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(_) => }
    }

    "should handle TransactionTimeout and fail the transaction" in {
      val definition = registerDefinition("spec-timeout", steps = Seq(logicalStep("step1", AlwaysOkRawParticipant)))
      val transactionId = "timeout-transaction"

      // Prepare executors hang (driving the global timeout); compensating executors succeed,
      // so the transaction lands in Failed rather than Suspended.
      val eventSourcedTestKit = createEventSourcedTestKit(
            name => if (name.endsWith("prepare")) {
              spawn(Behaviors.receiveMessage[StepExecutor.Command] {
                 case StepExecutor.QueryStatus(replyTo) =>
                    replyTo ! StepExecutor.State(status = StepExecutor.Ongoing)
                    Behaviors.same
                 case _ => Behaviors.same
              })
            } else createSuccessfulStepExecutor(),
            persistenceId = "test-saga-timeout-test",
            globalTimeout = 200.millis
      )

      start(eventSourcedTestKit, definition, transactionId)

      nextJournalEvent(eventSourcedTestKit, "test-saga-timeout-test") { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.Started(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-timeout-test", 10.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.PhaseFailed(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-timeout-test", 10.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-timeout-test", 10.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.TransactionFailed(_) => }
    }

    "handle ask timeout by querying status and eventually completing" in {
      val definition = registerDefinition("spec-asktimeout", steps = Seq(logicalStep("step1", AlwaysOkRawParticipant)))
      val transactionId = "timeout-query-status-transaction"

      def slowStepExecutorCreator(): ActorRef[StepExecutor.Command] = {
        spawn(Behaviors.receiveMessage[StepExecutor.Command] {
          case StepExecutor.Attach(transactionId, step, replyTo, traceId) =>
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

      val eventSourcedTestKit = createEventSourcedTestKit(_ => slowStepExecutorCreator(), persistenceId = "test-saga-ask-timeout", globalTimeout = 20.seconds)
      start(eventSourcedTestKit, definition, transactionId)

      nextJournalEvent(eventSourcedTestKit, "test-saga-ask-timeout") { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.Started(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-ask-timeout", 20.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-ask-timeout", 20.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(_) => }
      nextJournalEvent(eventSourcedTestKit, "test-saga-ask-timeout", 20.seconds) { case net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(_) => }
    }

  }

  /** Journal assertions run against the saga_v3 proto payload (the coordinator persists POs). */
  private def nextJournalEvent(
      kit: EventSourcedBehaviorTestKit[SagaTransactionCoordinator.Command, SagaTransactionCoordinator.Event, SagaTransactionCoordinator.State],
      pid: String,
      timeout: FiniteDuration = 20.seconds
  )(f: net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO.Event => Unit): Unit = {
    val po = kit.persistenceTestKit.expectNextPersistedType[net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO](pid, timeout)
    f(po.event)
  }
}

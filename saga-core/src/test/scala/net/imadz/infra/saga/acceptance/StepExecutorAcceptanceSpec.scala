package net.imadz.infra.saga.acceptance

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant
import net.imadz.infra.saga.SagaParticipant.{SagaResult, _}
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.StepExecutor
import net.imadz.infra.saga.StepExecutor.{CircuitBreakerSettings, State}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

/** G1 acceptance: StepExecutor-level criteria (AC-1.5 Attach semantics, AC-1.6 generation
  * numbers, AC-1.11 timeoutPerAttempt driving the engine timer). */
class StepExecutorAcceptanceSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka {
      |  actor {
      |    allow-java-serialization = on
      |    warn-about-java-serializer-usage = off
      |  }
      |  akka.actor.testkit.typed.single-expect-default = 10s
      |  akka.actor.testkit.typed.serialize-messages = off
      |  akka.actor.testkit.typed.serialization.verify = off
      |  akka.persistence.testkit.events.serialize = off
      |}
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with BeforeAndAfterEach with Eventually {

  private val ec = system.executionContext

  private def stepExecutorBehavior(persistenceId: String, breaker: CircuitBreakerSettings = CircuitBreakerSettings(3, 10.seconds, 1.second)) =
    StepExecutor[String, String, Any](
      persistenceId = PersistenceId.ofUniqueId(persistenceId),
      context = 0,
      defaultMaxRetries = 5,
      initialRetryDelay = 100.millis,
      circuitBreakerSettings = breaker,
      extendedSystem = system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem]
    )

  /** Participant whose prepare invocations each return a controllable promise. */
  class ControllableParticipant extends SagaParticipant[String, String, Any] {
    val pending = mutable.ListBuffer[Promise[Either[String, SagaResult[String]]]]()
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def doPrepare(transactionId: String, context: Any, traceId: String) = {
      val p = Promise[Either[String, SagaResult[String]]]()
      pending += p
      calls.incrementAndGet()
      p.future
    }
    override def doCommit(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("committed")))
    override def doCompensate(transactionId: String, context: Any, traceId: String) = Future.successful(Right(SagaResult("compensated")))
    override protected def customClassification: PartialFunction[Throwable, RetryableOrNotException] = PartialFunction.empty
  }

  "AC-1.5 Attach semantics" should {
    "start a Created step" in {
      val p = new ControllableParticipant
      val ref = spawn(stepExecutorBehavior("ac15-created"))
      val probe = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      val step = net.imadz.infra.saga.SagaTransactionStep[String, String, Any]("s1", PreparePhase, p, 3)
      ref ! StepExecutor.Attach("tx15", step, Some(probe.ref), "trace")
      eventually { p.pending should not be empty } // Attach is async — wait for the invocation
      p.pending.head.success(Right(SagaResult("prepared")))
      probe.expectMessage(10.seconds, StepExecutor.StepCompleted[String, String, Any]("tx15", "s1", SagaResult("prepared")))
    }

    "reply with the cached result on re-attach to a Succeeded step" in {
      val p = new ControllableParticipant
      val pid = "ac15-cached"
      val ref = spawn(stepExecutorBehavior(pid))
      val step = net.imadz.infra.saga.SagaTransactionStep[String, String, Any]("s1", PreparePhase, p, 3)
      val probe = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      ref ! StepExecutor.Attach("tx15c", step, Some(probe.ref), "trace")
      eventually { p.pending should not be empty }
      p.pending.head.success(Right(SagaResult("prepared")))
      probe.expectMessage(10.seconds, StepExecutor.StepCompleted[String, String, Any]("tx15c", "s1", SagaResult("prepared")))

      val ref2 = spawn(stepExecutorBehavior(pid)) // fresh incarnation, same journal
      val probe2 = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      ref2 ! StepExecutor.Attach("tx15c", step, Some(probe2.ref), "trace")
      probe2.expectMessage(10.seconds, StepExecutor.StepCompleted[String, String, Any]("tx15c", "s1", SagaResult("prepared")))
      p.calls.get() shouldBe 1 // no re-execution
    }

    "recover an Ongoing step exactly once under RetryIfOngoing (B1 regression: no double trigger)" in {
      val p = new ControllableParticipant
      val pid = "ac15-recover"
      val step = net.imadz.infra.saga.SagaTransactionStep[String, String, Any](
        "s1", PreparePhase, p, maxRetries = 3, retryWhenRecoveredOngoing = true)
      val ref = spawn(stepExecutorBehavior(pid))
      val probe = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      ref ! StepExecutor.Attach("tx15r", step, Some(probe.ref), "trace")
      eventually { p.calls.get() shouldBe 1 }

      // Simulate a crash: stop the actor, respawn from the same journal — the step replays as Ongoing.
      val ref2 = spawn(stepExecutorBehavior(pid))
      val probe2 = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      eventually {
        val q = createTestProbe[State[String, String, Any]]()
        ref2 ! StepExecutor.QueryStatus(q.ref)
        q.receiveMessage(5.seconds).status shouldBe StepExecutor.Ongoing
      }
      ref2 ! StepExecutor.Attach("tx15r", step, Some(probe2.ref), "trace")
      eventually { p.calls.get() shouldBe 2 } // exactly one recovery re-issue, not two

      // The recovered invocation (same generation) completes the step.
      p.pending.last.success(Right(SagaResult("recovered")))
      probe2.expectMessage(10.seconds, StepExecutor.StepCompleted[String, String, Any]("tx15r", "s1", SagaResult("recovered")))
    }

    "fail an Ongoing step under FailIfOngoing on re-attach" in {
      val p = new ControllableParticipant
      val pid = "ac15-failifongoing"
      val step = net.imadz.infra.saga.SagaTransactionStep[String, String, Any](
        "s1", PreparePhase, p, maxRetries = 3, retryWhenRecoveredOngoing = false)
      val ref = spawn(stepExecutorBehavior(pid))
      val probe = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      ref ! StepExecutor.Attach("tx15f", step, Some(probe.ref), "trace")
      eventually { p.calls.get() shouldBe 1 }

      val ref2 = spawn(stepExecutorBehavior(pid))
      val probe2 = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      eventually {
        val q = createTestProbe[State[String, String, Any]]()
        ref2 ! StepExecutor.QueryStatus(q.ref)
        q.receiveMessage(5.seconds).status shouldBe StepExecutor.Ongoing
      }
      ref2 ! StepExecutor.Attach("tx15f", step, Some(probe2.ref), "trace")
      val failed = probe2.expectMessageType[StepExecutor.StepFailed[String, String, Any]](10.seconds)
      failed.stepId shouldBe "s1"
      p.calls.get() shouldBe 1 // no re-execution
    }
  }

  "AC-1.6 generation numbers" should {
    "drop a late response from a superseded attempt and accept the current one" in {
      val p = new ControllableParticipant
      val ref = spawn(stepExecutorBehavior("ac16-gen"))
      val probe = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      val step = net.imadz.infra.saga.SagaTransactionStep[String, String, Any]("s1", PreparePhase, p, maxRetries = 5, timeoutDuration = 200.millis)
      ref ! StepExecutor.Attach("tx16", step, Some(probe.ref), "trace")

      // attempt 1 in flight; timeout fires at 200ms -> retries becomes 1, attempt 2 dispatched
      eventually {
        val q = createTestProbe[State[String, String, Any]]()
        ref ! StepExecutor.QueryStatus(q.ref)
        val s = q.receiveMessage(5.seconds)
        s.status shouldBe StepExecutor.Ongoing
        s.retries shouldBe 1
      }
      val late = p.pending.find(_.future.value.isEmpty).get // attempt 1's promise (first created)
      late.success(Right(SagaResult("late-attempt-1")))

      eventually {
        val q = createTestProbe[State[String, String, Any]]()
        ref ! StepExecutor.QueryStatus(q.ref)
        q.receiveMessage(5.seconds).status shouldBe StepExecutor.Ongoing // late success NOT accepted
      }

      // attempt 2 must be dispatched before its promise can be completed
      eventually { p.calls.get() shouldBe 2 }

      // attempt 2 completes -> accepted
      p.pending.last.success(Right(SagaResult("attempt-2")))
      probe.expectMessage(10.seconds, StepExecutor.StepCompleted[String, String, Any]("tx16", "s1", SagaResult("attempt-2")))
    }

    "drop a stale TimedOut from a superseded attempt" in {
      val p = new ControllableParticipant
      val ref = spawn(stepExecutorBehavior("ac16-timeout"))
      val probe = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      val step = net.imadz.infra.saga.SagaTransactionStep[String, String, Any]("s1", PreparePhase, p, maxRetries = 5, timeoutDuration = 200.millis)
      ref ! StepExecutor.Attach("tx16t", step, Some(probe.ref), "trace")
      eventually {
        val q = createTestProbe[State[String, String, Any]]()
        ref ! StepExecutor.QueryStatus(q.ref)
        q.receiveMessage(5.seconds).retries shouldBe 1
      }
      ref ! StepExecutor.TimedOut(1, Some(probe.ref)) // stale generation
      eventually {
        val q = createTestProbe[State[String, String, Any]]()
        ref ! StepExecutor.QueryStatus(q.ref)
        q.receiveMessage(5.seconds).retries shouldBe 1 // unchanged
      }
    }
  }

  "AC-1.11 timeoutPerAttempt" should {
    "drive the engine timer from the step timeout" in {
      val p = new ControllableParticipant
      val ref = spawn(stepExecutorBehavior("ac111-timer"))
      val step = net.imadz.infra.saga.SagaTransactionStep[String, String, Any]("s1", PreparePhase, p, maxRetries = 5, timeoutDuration = 150.millis)
      val probe = createTestProbe[StepExecutor.StepResult[String, String, Any]]()
      ref ! StepExecutor.Attach("tx111", step, Some(probe.ref), "trace")
      eventually {
        val q = createTestProbe[State[String, String, Any]]()
        ref ! StepExecutor.QueryStatus(q.ref)
        q.receiveMessage(5.seconds).retries shouldBe 1
      }
    }
  }
}

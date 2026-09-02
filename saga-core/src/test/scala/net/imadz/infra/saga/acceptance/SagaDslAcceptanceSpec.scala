package net.imadz.infra.saga.acceptance

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import net.imadz.infra.saga.SagaParticipant.SagaResult
import net.imadz.infra.saga.SagaPhase._
import net.imadz.infra.saga.SagaTransactionCoordinator._
import net.imadz.infra.saga.{SagaTransactionCoordinator, StepDescriptor, StepExecutor, SagaTransactionStep}
import net.imadz.infra.saga.dsl.{ErrorAction, ErrorRules, PhaseAsk, RecoveryBehavior, ResiliencePolicy, SagaDefinition, SagaRegistry, SagaStep}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.util.Success

/** G1 acceptance: DSL-level and coordinator-level criteria (AC-1.1/1.2/1.3/1.7/1.8/1.10/1.11). */
class SagaDslAcceptanceSpec extends ScalaTestWithActorTestKit(
  ConfigFactory.parseString(
    """
      |akka {
      |  actor {
      |    allow-java-serialization = on
      |    warn-about-java-serializer-usage = off
      |  }
      |  akka.test.single-expect-default = 10s
      |  akka.actor.testkit.typed.single-expect-default = 10s
      |  akka.actor.testkit.typed.serialize-messages = off
      |  akka.actor.testkit.typed.serialize-creators = off
      |  akka.actor.testkit.typed.serialization.verify = off
      |  akka.persistence.testkit.events.serialize = off
      |}
      |""".stripMargin
  ).withFallback(EventSourcedBehaviorTestKit.config)
) with AnyWordSpecLike with BeforeAndAfterEach {

  import TestSagaSupport._

  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
  private implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

  private def coordinatorKit(pid: String, factory: String => ActorRef[StepExecutor.Command], globalTimeout: FiniteDuration = 20.seconds) =
    EventSourcedBehaviorTestKit[Command, Event, State](
      system,
      SagaTransactionCoordinator(PersistenceId.ofUniqueId(pid), factory, globalTimeout)(ec, 5.seconds)
    )

  private def argsBytesOf(args: TransferArgs): Array[Byte] =
    net.imadz.infra.saga.dsl.ArgsCodec.playJson[TransferArgs].encode(args)

  private def completingExecutor(calls: java.util.concurrent.atomic.AtomicInteger): ActorRef[StepExecutor.Command] =
    spawn(Behaviors.receiveMessage[StepExecutor.Command] {
      case StepExecutor.Attach(transactionId, step, replyTo, _) =>
        calls.incrementAndGet()
        replyTo.foreach(_ ! StepExecutor.StepCompleted(transactionId, step.stepId, SagaResult.empty()))
        Behaviors.stopped
      case qs: StepExecutor.QueryStatus[_, _, _] =>
        qs.replyTo.asInstanceOf[ActorRef[StepExecutor.State[Any, Any, Any]]] !
          StepExecutor.State[Any, Any, Any](status = StepExecutor.Succeed, result = Some(SagaResult.empty[Any]()))
        Behaviors.same
      case _ => Behaviors.same
    })

  private def hangingExecutor(): ActorRef[StepExecutor.Command] =
    spawn(Behaviors.receiveMessage[StepExecutor.Command] { case _ => Behaviors.same })

  // ------------------------------------------------------------------
  // AC-1.1 — expand correctness
  // ------------------------------------------------------------------
  "AC-1.1 SagaDefinition.expand" should {
    "expand two full participants and one prepare/commit-only participant, skipping unbound phases" in {
      val p1 = new CountingParticipant("p1")(ec, scheduler)
      val p2 = new CountingParticipant("p2")(ec, scheduler)
      val pc = new PrepareCommitOnlyParticipant("pc")(ec, scheduler)
      val definition = registerDefinition("ac11", steps = Seq(
        logicalStep("out", p1, group = 1),
        logicalStep("in", p2, group = 1),
        logicalStep("pc", pc, group = 2)))

      val expanded = definition.expand(TransferArgs("a", "b", 10)).get
      expanded.map(s => (s.stepId, s.phase)) should contain theSameElementsAs List(
        ("out", PreparePhase), ("out", CommitPhase), ("out", CompensatePhase),
        ("in", PreparePhase), ("in", CommitPhase), ("in", CompensatePhase),
        ("pc", PreparePhase), ("pc", CommitPhase))
      expanded.filter(_.stepGroup == 1).size shouldBe 6
      expanded.filter(_.stepGroup == 2).size shouldBe 2
      expanded.find(s => s.stepId == "pc" && s.phase == CompensatePhase) shouldBe None
    }
  }

  // ------------------------------------------------------------------
  // AC-1.2 — dual-track error classification
  // ------------------------------------------------------------------
  "AC-1.2 dual-track classification" should {
    val rules = ErrorRules[String](
      business = { case "60003" => ErrorAction.NonRetryable; case "60009" => ErrorAction.Retryable },
      describe = (e: String) => s"iMadzError($e)"
    )

    "classify a matched business error on the business track" in {
      rules.classifyBusiness("60003").isInstanceOf[net.imadz.infra.saga.SagaParticipant.NonRetryableFailure] shouldBe true
      rules.classifyBusiness("60009").isInstanceOf[net.imadz.infra.saga.SagaParticipant.RetryableFailure] shouldBe true
      rules.classifyBusiness("60009").message shouldBe "iMadzError(60009)"
    }
    "default unmatched business errors to NonRetryable" in {
      rules.classifyBusiness("60077").isInstanceOf[net.imadz.infra.saga.SagaParticipant.NonRetryableFailure] shouldBe true
    }
    "route AskTimeoutException to Retryable on the thrown track (default matrix)" in {
      rules.classifyThrown(new akka.pattern.AskTimeoutException("timed out"))
        .isInstanceOf[net.imadz.infra.saga.SagaParticipant.RetryableFailure] shouldBe true
      rules.classifyThrown(new IllegalArgumentException("bad"))
        .isInstanceOf[net.imadz.infra.saga.SagaParticipant.NonRetryableFailure] shouldBe true
    }
    "classify mapReply synchronous throws through the thrown track" in {
      val throwing = new CountingParticipant("throwing")(ec, scheduler) {
        override val prepareBinding: Option[PhaseAsk[String, String, Any]] =
          Some(PhaseAsk.direct((_, _, _) => throw new IllegalArgumentException("bad")))
      }
      val outcome = scala.concurrent.Await.result(throwing.prepare("tx", (), "trace"), 5.seconds)
      outcome.left.toOption.map(_.isInstanceOf[net.imadz.infra.saga.SagaParticipant.NonRetryableFailure]) shouldBe Some(true)
    }
    "classify a real ask timeout as Retryable" in {
      val neverReply = spawn(Behaviors.receiveMessage[String] { case _ => Behaviors.same })
      val p = new CountingParticipant("asktimeout")(ec, scheduler) {
        override val prepareBinding: Option[PhaseAsk[String, String, Any]] =
          Some(PhaseAsk.ask[String, String, String, String, Any](_ => neverReply, (txId, _) => s"cmd-$txId", r => Right(SagaResult(r))))
        override val askTimeout: FiniteDuration = 150.millis
      }
      val outcome = scala.concurrent.Await.result(p.prepare("tx", (), "trace"), 5.seconds)
      outcome.left.toOption.map(_.isInstanceOf[net.imadz.infra.saga.SagaParticipant.RetryableFailure]) shouldBe Some(true)
    }
  }

  // ------------------------------------------------------------------
  // AC-1.3 — StartSaga idempotency matrix
  // ------------------------------------------------------------------
  "AC-1.3 StartSaga idempotency matrix" should {
    "reject UnknownDefinition without events" in {
      val kit = coordinatorKit("ac13-unknown", _ => hangingExecutor())
      val startProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-unknown", "no-such-definition", 1, argsBytesOf(TransferArgs("a", "b", 1)), "t", singleStep = false, Some(startProbe.ref), None))
      startProbe.expectMessage(UnknownDefinition)
      intercept[AssertionError](kit.persistenceTestKit.expectNextPersistedType[TransactionStarted]("ac13-unknown", 1.second))
    }

    "reject PreCheckFailed without events" in {
      import net.imadz.infra.saga.dsl.ArgsCodec
      val p = new CountingParticipant("ac13-precheck")(ec, scheduler)
      val definition = SagaDefinition[String, Any, TransferArgs](
        name = "ac13-precheck-def", version = 1,
        argsCodec = ArgsCodec.playJson[TransferArgs],
        steps = _ => Seq(logicalStep("s", p)),
        preCheck = args => if (args.amount > 0) Right(args) else Left("40001"),
        errorText = e => (e, s"invalid amount: $e")
      )
      SagaRegistry.register(definition)
      val kit = coordinatorKit("ac13-precheck", _ => hangingExecutor())
      val startProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-pc", "ac13-precheck-def", 1, argsBytesOf(TransferArgs("a", "b", -5)), "t", singleStep = false, Some(startProbe.ref), None))
      startProbe.expectMessage(PreCheckFailed("40001", "invalid amount: 40001"))
      intercept[AssertionError](kit.persistenceTestKit.expectNextPersistedType[TransactionStarted]("ac13-precheck", 1.second))
    }

    "reject MaterializeFailed when args cannot be decoded" in {
      registerDefinition("ac13-matdef", steps = Seq(logicalStep("s", new CountingParticipant("m")(ec, scheduler))))
      val kit = coordinatorKit("ac13-mat", _ => hangingExecutor())
      val startProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-mat", "ac13-matdef", 1, "not-json".getBytes, "t", singleStep = false, Some(startProbe.ref), None))
      startProbe.expectMessage(MaterializeFailed)
      intercept[AssertionError](kit.persistenceTestKit.expectNextPersistedType[TransactionStarted]("ac13-mat", 1.second))
    }

    "answer AlreadyRunning (coarse) for the same key while in flight" in {
      registerDefinition("ac13-run", steps = Seq(logicalStep("s", new CountingParticipant("r")(ec, scheduler))))
      val kit = coordinatorKit("ac13-run-tx", _ => hangingExecutor())
      val args = argsBytesOf(TransferArgs("a", "b", 1))
      val startProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-run", "ac13-run", 1, args, "t", singleStep = false, Some(startProbe.ref), None))
      startProbe.expectMessage(Started)

      val secondProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-run", "ac13-run", 1, args, "t", singleStep = false, Some(secondProbe.ref), None))
      val already = secondProbe.expectMessageType[AlreadyRunning]
      already.snapshot.status shouldBe InProgress.toString
      already.snapshot.steps.map(_.stepId).distinct shouldBe List("s") // one spec per (stepId, phase)
      already.snapshot.steps.map(_.status) should contain only "Unknown" // current group steps are Unknown in the coarse snapshot
    }

    "reject ConflictingArgs for the same txId with different args" in {
      registerDefinition("ac13-conflict", steps = Seq(logicalStep("s", new CountingParticipant("c")(ec, scheduler))))
      val kit = coordinatorKit("ac13-conflict-tx", _ => hangingExecutor())
      val startProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-conflict", "ac13-conflict", 1, argsBytesOf(TransferArgs("a", "b", 1)), "t", singleStep = false, Some(startProbe.ref), None))
      startProbe.expectMessage(Started)
      val secondProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-conflict", "ac13-conflict", 1, argsBytesOf(TransferArgs("a", "b", 2)), "t", singleStep = false, Some(secondProbe.ref), None))
      secondProbe.expectMessage(ConflictingArgs)
    }

    "answer AlreadyFinished with the historical result for terminal transactions" in {
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      registerDefinition("ac13-done", steps = Seq(logicalStep("s", new CountingParticipant("d")(ec, scheduler))))
      val kit = coordinatorKit("ac13-done-tx", _ => completingExecutor(calls))
      val args = argsBytesOf(TransferArgs("a", "b", 3))
      val startProbe = createTestProbe[SagaStartReply]()
      val completionProbe = createTestProbe[TransactionResult]()
      kit.runCommand(StartSaga("tx-done", "ac13-done", 1, args, "t", singleStep = false, Some(startProbe.ref), Some(completionProbe.ref)))
      startProbe.expectMessage(Started)
      completionProbe.receiveMessage(10.seconds).successful shouldBe true // reach the terminal state before restarting

      kit.restart() // terminal state survives restarts (entity thenStop + replay)
      val secondProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-done", "ac13-done", 1, args, "t", singleStep = false, Some(secondProbe.ref), None))
      val finished = secondProbe.expectMessageType[AlreadyFinished]
      finished.successful shouldBe true
      finished.steps.map(_.stepId).distinct shouldBe List("s")
      // successful transaction: prepare/commit ran, compensate never did
      finished.steps.map(s => (s.phase, s.status)) should contain theSameElementsAs Seq(
        ("prepare", "Succeeded"), ("commit", "Succeeded"), ("compensate", "Unknown"))
    }
  }

  // ------------------------------------------------------------------
  // AC-1.7 — re-entry safety
  // ------------------------------------------------------------------
  "AC-1.7 re-entry safety" should {
    "tolerate ProceedNextGroup and RetryCurrentPhase while a dispatch is in flight" in {
      registerDefinition("ac17", steps = Seq(logicalStep("s", new CountingParticipant("r")(ec, scheduler))))
      val kit = coordinatorKit("ac17-tx", _ => hangingExecutor())
      val startProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-17", "ac17", 1, argsBytesOf(TransferArgs("a", "b", 1)), "t", singleStep = false, Some(startProbe.ref), None))
      startProbe.expectMessage(Started)

      // Both re-drives must be absorbed without crashing the entity (no InvalidActorNameException).
      kit.runCommand(ProceedNextGroup(None))
      kit.runCommand(RetryCurrentPhase(None))

      val statusProbe = createTestProbe[Option[StatusSnapshot]]()
      kit.runCommand(GetTransactionStatus("tx-17", statusProbe.ref))
      statusProbe.receiveMessage().map(_.status) shouldBe Some(InProgress.toString)
    }
  }

  // ------------------------------------------------------------------
  // AC-1.8 — definition drift vs tunable drift
  // ------------------------------------------------------------------
  "AC-1.8 drift handling" should {
    "suspend when the structural step plan drifts" in {
      registerDefinition("ac18-drift", steps = Seq(logicalStep("stepA", new CountingParticipant("a")(ec, scheduler))))
      val kit = coordinatorKit("ac18-drift-tx", _ => hangingExecutor())
      val startProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-18", "ac18-drift", 1, argsBytesOf(TransferArgs("a", "b", 1)), "t", singleStep = false, Some(startProbe.ref), None))
      startProbe.expectMessage(Started)

      // Redeploy: v1 now has a different structural plan
      registerDefinition("ac18-drift", steps = Seq(logicalStep("stepB", new CountingParticipant("b")(ec, scheduler))))
      kit.restart() // cache is node-local and cleared by restart; replay drives RecoveredInProgress,
      // which fails materialization and persists TransactionSuspended (the entity then stops).

      // The suspension is durable — assert it through the journal POs.
      import net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO
      val po1 = kit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinatorEventPO]("ac18-drift-tx", 20.seconds)
      po1.event.isInstanceOf[SagaTransactionCoordinatorEventPO.Event.Started] shouldBe true
      val po2 = kit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinatorEventPO]("ac18-drift-tx", 20.seconds)
      po2.event match {
        case SagaTransactionCoordinatorEventPO.Event.Suspended(s) => s.reason should include("materialize")
        case other => fail(s"expected Suspended, got $other")
      }
    }

    "tolerate resilience-parameter drift and keep the transaction alive" in {
      registerDefinition("ac18-tune", steps = Seq(logicalStep("stepA", new CountingParticipant("a")(ec, scheduler), maxRetries = 3)))
      val kit = coordinatorKit("ac18-tune-tx", _ => hangingExecutor())
      val startProbe = createTestProbe[SagaStartReply]()
      kit.runCommand(StartSaga("tx-18t", "ac18-tune", 1, argsBytesOf(TransferArgs("a", "b", 1)), "t", singleStep = false, Some(startProbe.ref), None))
      startProbe.expectMessage(Started)

      registerDefinition("ac18-tune", steps = Seq(logicalStep("stepA", new CountingParticipant("a2")(ec, scheduler), maxRetries = 9)))
      kit.restart()

      val statusProbe = createTestProbe[Option[StatusSnapshot]]()
      kit.runCommand(GetTransactionStatus("tx-18t", statusProbe.ref))
      statusProbe.receiveMessage().map(_.status) shouldBe Some(InProgress.toString)
    }
  }

  // ------------------------------------------------------------------
  // AC-1.10 — journal content
  // ------------------------------------------------------------------
  "AC-1.10 journal content" should {
    "carry definition ref + args + descriptors and never a participant payload" in {
      import net.imadz.infra.saga.persistence.converters.SagaCoordinatorProtoConverters
      import net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO
      import scala.collection.JavaConverters._
      val classic = system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem]
      val converter = new SagaCoordinatorProtoConverters {
        override def system: akka.actor.ExtendedActorSystem = classic
      }
      val step = SagaTransactionStep[String, String, Any]("s1", PreparePhase, AlwaysOkRawParticipant, 3, 5.seconds, stepGroup = 2)
      val event = TransactionStarted("tx-110", "def", 7, Array[Byte](1, 2, 3), "hash", List(StepDescriptor.of(step)), "trace", singleStep = false)
      val po = converter.TransactionStartedConv.toProto(event)
      po.definitionName shouldBe "def"
      po.definitionVersion shouldBe 7
      po.argsHash shouldBe "hash"
      po.args.size() shouldBe 3
      po.steps.head.participantName shouldBe "AlwaysOkRawParticipant$"
      po.steps.head.stepGroup shouldBe 2
      po.steps.head.maxRetries shouldBe 3
      po.steps.head.timeoutDurationMillis shouldBe 5000

      // structural: no participant-payload field anywhere on the coordinator event union
      val fieldNames = SagaTransactionCoordinatorEventPO.javaDescriptor.getFields.asScala.map(_.getName).toSeq
      fieldNames.exists(_.toLowerCase.contains("participant")) shouldBe false

      val back = converter.TransactionStartedConv.fromProto(po)
      back.transactionId shouldBe event.transactionId
      back.definitionName shouldBe event.definitionName
      back.definitionVersion shouldBe event.definitionVersion
      back.argsBytes.toSeq shouldBe event.argsBytes.toSeq
      back.steps shouldBe event.steps
      back.traceId shouldBe event.traceId
    }
  }

  // ------------------------------------------------------------------
  // AC-1.4 — crash recovery
  // ------------------------------------------------------------------
  "AC-1.4 crash recovery" should {
    "rebuild steps from (definition, args) after a coordinator crash and resume without duplicate execution" in {
      import scala.concurrent.Promise
      import scala.jdk.CollectionConverters._
      val calls = new java.util.concurrent.atomic.AtomicInteger(0)
      val pending = new java.util.concurrent.ConcurrentLinkedQueue[Promise[Either[String, SagaResult[String]]]]()
      val participant = new net.imadz.infra.saga.SagaParticipant[String, String, Any] {
        override def doPrepare(transactionId: String, context: Any, traceId: String) = {
          calls.incrementAndGet()
          val p = Promise[Either[String, SagaResult[String]]]()
          pending.add(p)
          p.future
        }
        override def doCommit(transactionId: String, context: Any, traceId: String) = scala.concurrent.Future.successful(Right(SagaResult("committed")))
        override def doCompensate(transactionId: String, context: Any, traceId: String) = scala.concurrent.Future.successful(Right(SagaResult("compensated")))
        override protected def customClassification: PartialFunction[Throwable, net.imadz.infra.saga.SagaParticipant.RetryableOrNotException] = PartialFunction.empty
      }
      val definition = registerDefinition("ac14-def", steps = Seq(
        SagaStep("s", participant, ResiliencePolicy(maxRetries = 3, recovery = RecoveryBehavior.RetryIfOngoing), 1)))

      // real executors with a deterministic pid per executor name; they survive the coordinator crash
      val execRefs = new java.util.concurrent.ConcurrentHashMap[String, ActorRef[StepExecutor.Command]]()
      def factory(name: String): ActorRef[StepExecutor.Command] = {
        val existing = execRefs.get(name)
        if (existing != null) existing
        else {
          val ref = spawn(StepExecutor[String, String, Any](
            PersistenceId.ofUniqueId(s"ac14-exec-$name"),
            context = 0,
            defaultMaxRetries = 5,
            initialRetryDelay = 100.millis,
            circuitBreakerSettings = StepExecutor.CircuitBreakerSettings(5, 10.seconds, 1.second),
            extendedSystem = system.classicSystem.asInstanceOf[akka.actor.ExtendedActorSystem]))
          execRefs.put(name, ref)
          ref
        }
      }

      val kit = coordinatorKit("ac14-tx", factory)
      val startProbe = createTestProbe[SagaStartReply]()
      val completionProbe = createTestProbe[TransactionResult]()
      kit.runCommand(StartSaga("tx-14", "ac14-def", 1, argsBytesOf(TransferArgs("a", "b", 1)), "t", singleStep = false, Some(startProbe.ref), Some(completionProbe.ref)))
      startProbe.expectMessage(Started)
      eventually { calls.get() shouldBe 1 } // original dispatch in flight (promise pending)

      // coordinator crash: journal replay → RecoveredInProgress → steps rematerialized from
      // (definitionRef, args) — the participant is rebuilt, never read back from the journal.
      kit.restart()

      // the surviving executor replays as Ongoing; RetryIfOngoing re-issues the SAME generation once
      eventually { calls.get() shouldBe 2 }

      // the recovered invocation drives the transaction to completion end to end. The in-memory
      // completion channel is lost across the crash by design (durable callers poll statusOf),
      // so terminal completion is asserted through the journal POs.
      pending.asScala.last.success(Right(SagaResult("recovered")))
      import net.imadz.infra.saga.proto.saga_v3.SagaTransactionCoordinatorEventPO
      def nextPo: SagaTransactionCoordinatorEventPO.Event =
        kit.persistenceTestKit.expectNextPersistedType[SagaTransactionCoordinatorEventPO]("ac14-tx", 20.seconds).event
      nextPo match { case SagaTransactionCoordinatorEventPO.Event.Started(_) => case o => fail(s"expected Started, got $o") }
      nextPo match { case SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(_) => case o => fail(s"expected PhaseSucceeded, got $o") }
      nextPo match { case SagaTransactionCoordinatorEventPO.Event.PhaseSucceeded(_) => case o => fail(s"expected PhaseSucceeded, got $o") }
      nextPo match {
        case SagaTransactionCoordinatorEventPO.Event.TransactionCompleted(c) => c.transactionId shouldBe "tx-14"
        case o => fail(s"expected TransactionCompleted, got $o")
      }
      calls.get() shouldBe 2 // exactly one recovery re-issue; terminal steps never re-executed
    }
  }

  // ------------------------------------------------------------------
  // AC-1.11 — resilience policy activation (pure-level)
  // ------------------------------------------------------------------
  "AC-1.11 resilience policy activation" should {
    "map ResiliencePolicy fields into the expanded engine steps" in {
      val p = new CountingParticipant("ac111")(ec, scheduler)
      val definition = registerDefinition(
        "ac111-def",
        defaultResilience = ResiliencePolicy(maxRetries = 5, recovery = RecoveryBehavior.RetryIfOngoing,
          circuitBreaker = Some(StepExecutor.CircuitBreakerSettings(4, 2.seconds, 3.seconds))),
        steps = Seq(
          logicalStep("inherit", p),
          SagaStep("explicit", p, ResiliencePolicy(maxRetries = 7, recovery = RecoveryBehavior.FailIfOngoing), 1)))

      val expanded = definition.expand(TransferArgs("a", "b", 1)).get
      val inherit = expanded.filter(_.stepId == "inherit")
      inherit.map(_.maxRetries) should contain only 5
      inherit.map(_.retryWhenRecoveredOngoing) should contain only true
      inherit.map(_.circuitBreaker.map(_.maxFailures)) should contain only Some(4)
      val explicit = expanded.filter(_.stepId == "explicit")
      explicit.map(_.maxRetries) should contain only 7
      explicit.map(_.retryWhenRecoveredOngoing) should contain only false
    }
  }
}

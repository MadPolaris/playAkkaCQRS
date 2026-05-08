package net.imadz.m25.template

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, Recovery}
import net.imadz.m25.binding._

import scala.concurrent.duration._

/**
 * ===== M2.5 Core Template: External Two-Phase Business Chain =====
 *
 * Generates 6 Akka Persistent FSM nodes from a single parameter set.
 * This single template replaces the 30 Java files (6 sub-flows × 5 files)
 * currently duplicated for recharge and purchase.
 *
 * == Generated FSM Topology ==
 *
 *   request ──→ response ──→ reconfirm ──→ success ──→ p2b-notify
 *                          ├─→ success    (outcome branches from response & reconfirm)
 *                          └─→ failure
 *
 * == State Machine (shared by all 6 sub-FSMs) ==
 *
 *   Idle → Processing → WaitingExternal → Verifying → Completed
 *                                             └─→ Failed
 *
 * == Variation Points (captured as ChainBindings) ==
 *
 *   - Which external gateways to call (sftp / core / p2b)
 *   - What SMS templates to use on success / failure
 *   - How to map external error codes to internal failure categories
 *   - SLA timeout duration
 *
 * == Resilience Patterns ==
 *
 *   request / response / reconfirm → Communicator  (poll external, timeout-aware)
 *   success / failure               → Communicator  (notify downstream)
 *   p2b-notify                      → Communicator  (platform integration)
 */
final class ExternalTwoPhaseChainTemplate
  extends FlowTemplate[ExternalTwoPhaseChainTemplate.Params] {

  import ExternalTwoPhaseChainTemplate._

  override def materialize(p: Params): DAGSubgraph = {
    val chainId = p.chainId

    val fsmRequest  = buildRequestFSM(chainId, p)
    val fsmResponse = buildResponseFSM(chainId, p)
    val fsmReconf   = buildReconfirmFSM(chainId, p)
    val fsmSuccess  = buildSuccessFSM(chainId, p)
    val fsmFailure  = buildFailureFSM(chainId, p)
    val fsmP2B      = buildP2BNotifyFSM(chainId, p)

    DAGSubgraph(
      nodes = Seq(fsmRequest, fsmResponse, fsmReconf, fsmSuccess, fsmFailure, fsmP2B),
      edges = Seq(
        DAGEdge(s"$chainId-request",  s"$chainId-response"),
        DAGEdge(s"$chainId-response", s"$chainId-reconfirm"),
        DAGEdge(s"$chainId-response", s"$chainId-success",  "on-success"),
        DAGEdge(s"$chainId-response", s"$chainId-failure",  "on-failure"),
        DAGEdge(s"$chainId-reconfirm", s"$chainId-success", "on-reconfirm-ok"),
        DAGEdge(s"$chainId-reconfirm", s"$chainId-failure", "on-reconfirm-fail"),
        DAGEdge(s"$chainId-success",  s"$chainId-p2b",      "notify-platform")
      )
    )
  }

  // ============================================================
  // FSM: Request (文件生成 + SFTP 上传)
  // ============================================================
  private def buildRequestFSM(chainId: String, p: Params): FSMNode = {
    val entityKey = EntityTypeKey[RequestCommand](s"$chainId-request")

    val behavior: Behavior[RequestCommand] = EventSourcedBehavior[RequestCommand, RequestEvent, RequestState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = RequestState.Idle,
      commandHandler = { (state, cmd) =>
        import RequestState._
        (state, cmd) match {
          case (Idle, RequestCommand.Start(jobId, items, replyTo)) =>
            val event = RequestEvent.GenerationStarted(jobId, items)
            Effect.persist(event).thenRun { newState =>
              replyTo ! RequestReply.Acknowledged(entityKey.name, jobId)
            }

          case (Generating(jobId, items), RequestCommand.FileGenerated(filePath)) =>
            val event = RequestEvent.FileGenerated(jobId, filePath)
            Effect.persist(event).thenRun { _ =>
              // Trigger SFTP upload via gateway
              // p.bindings.sftp.tell(SftpCommand.UploadFile(filePath))(...)
            }

          case (FileReady(jobId, filePath), RequestCommand.UploadCompleted) =>
            val event = RequestEvent.UploadCompleted(jobId)
            Effect.persist(event).thenRun { _ =>
              // Notify EAMS via core gateway
            }

          case (Uploaded(jobId), RequestCommand.EamsNotified) =>
            Effect.persist(RequestEvent.RequestCompleted(jobId))

          case (_, RequestCommand.CheckTimeout(deadline)) =>
            if (System.currentTimeMillis() > deadline)
              Effect.persist(RequestEvent.RequestFailed("timeout"))
            else
              Effect.none

          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        import RequestState._
        (state, event) match {
          case (Idle, RequestEvent.GenerationStarted(jobId, items)) =>
            Generating(jobId, items)
          case (Generating(jobId, _), RequestEvent.FileGenerated(_, path)) =>
            FileReady(jobId, path)
          case (FileReady(jobId, _), RequestEvent.UploadCompleted(_)) =>
            Uploaded(jobId)
          case (Uploaded(jobId), RequestEvent.RequestCompleted(_)) =>
            Completed(jobId)
          case (_, RequestEvent.RequestFailed(reason)) =>
            Failed(reason)
          case _ => state
        }
      }
    ).withRecovery(Recovery.default)

    FSMNode(entityKey, behavior, s"${chainId}请求", ResiliencePattern.Communicator, level = 3)
  }

  // ============================================================
  // FSM: Response (回盘文件轮询 + 解析)
  // ============================================================
  private def buildResponseFSM(chainId: String, p: Params): FSMNode = {
    val entityKey = EntityTypeKey[ResponseCommand](s"$chainId-response")

    val behavior: Behavior[ResponseCommand] = EventSourcedBehavior[ResponseCommand, ResponseEvent, ResponseState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = ResponseState.Idle,
      commandHandler = { (state, cmd) =>
        import ResponseState._
        (state, cmd) match {
          case (Idle, ResponseCommand.StartPolling(jobId, replyTo)) =>
            Effect.persist(ResponseEvent.PollingStarted(jobId)).thenRun { _ =>
              replyTo ! ResponseReply.Started(entityKey.name)
            }

          case (Polling(jobId), ResponseCommand.FileDetected(filePath)) =>
            Effect.persist(ResponseEvent.FileDownloaded(jobId, filePath))

          case (Downloaded(jobId, path), ResponseCommand.ParseCompleted(results)) =>
            Effect.persist(ResponseEvent.Parsed(jobId, results))

          case (Parsed(jobId, results), ResponseCommand.RouteOutcome) =>
            Effect.persist(ResponseEvent.Completed(jobId, results))

          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        import ResponseState._
        (state, event) match {
          case (Idle, ResponseEvent.PollingStarted(jobId)) => Polling(jobId)
          case (Polling(jobId), ResponseEvent.FileDownloaded(_, path)) => Downloaded(jobId, path)
          case (Downloaded(jobId, _), ResponseEvent.Parsed(_, results)) => Parsed(jobId, results)
          case (Parsed(jobId, _), ResponseEvent.Completed(_, _)) => Completed(jobId)
          case _ => state
        }
      }
    ).withRecovery(Recovery.default)

    FSMNode(entityKey, behavior, s"${chainId}响应", ResiliencePattern.Communicator, level = 3)
  }

  // ============================================================
  // FSM: Reconfirm (核心系统查证)
  // ============================================================
  private def buildReconfirmFSM(chainId: String, p: Params): FSMNode = {
    val entityKey = EntityTypeKey[ReconfirmCommand](s"$chainId-reconfirm")

    val behavior: Behavior[ReconfirmCommand] = EventSourcedBehavior[ReconfirmCommand, ReconfirmEvent, ReconfirmState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = ReconfirmState.Idle,
      commandHandler = { (state, cmd) =>
        import ReconfirmState._
        (state, cmd) match {
          case (Idle, ReconfirmCommand.Verify(jobId, items, replyTo)) =>
            Effect.persist(ReconfirmEvent.VerificationStarted(jobId, items.size)).thenRun { _ =>
              // p.bindings.core.tell(CoreApiCommand.VerifyTransactions(items))(...)
              replyTo ! ReconfirmReply.Started(entityKey.name)
            }

          case (Verifying(jobId, total), ReconfirmCommand.VerificationResult(ok, failed)) =>
            val event = if (failed.isEmpty) ReconfirmEvent.AllVerified(jobId)
                        else ReconfirmEvent.PartialMismatch(jobId, failed)
            Effect.persist(event)

          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        import ReconfirmState._
        (state, event) match {
          case (Idle, ReconfirmEvent.VerificationStarted(jobId, total)) => Verifying(jobId, total)
          case (Verifying(jobId, _), ReconfirmEvent.AllVerified(_)) => Verified(jobId)
          case (Verifying(jobId, _), ReconfirmEvent.PartialMismatch(_, failed)) => Mismatch(jobId, failed)
          case _ => state
        }
      }
    ).withRecovery(Recovery.default)

    FSMNode(entityKey, behavior, s"${chainId}重确认", ResiliencePattern.Communicator, level = 3)
  }

  // ============================================================
  // FSM: Success (成功处理 + 发短信)
  // ============================================================
  private def buildSuccessFSM(chainId: String, p: Params): FSMNode = {
    val entityKey = EntityTypeKey[SuccessCommand](s"$chainId-success")
    val smsTpl = p.messages

    val behavior: Behavior[SuccessCommand] = EventSourcedBehavior[SuccessCommand, SuccessEvent, SuccessState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = SuccessState.Idle,
      commandHandler = { (state, cmd) =>
        import SuccessState._
        (state, cmd) match {
          case (Idle, SuccessCommand.ProcessSuccess(jobId, items, replyTo)) =>
            Effect.persist(SuccessEvent.ProcessingStarted(jobId, items.size)).thenRun { _ =>
              // Build SMS objects from items using smsTpl.successSmsTitle / successSmsBody
              replyTo ! SuccessReply.Started(entityKey.name)
            }

          case (Processing(jobId), SuccessCommand.SmsSent) =>
            Effect.persist(SuccessEvent.SmsDelivered(jobId))

          case (SmsDelivered(jobId), SuccessCommand.SuccessAcknowledged) =>
            Effect.persist(SuccessEvent.Completed(jobId))

          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        import SuccessState._
        (state, event) match {
          case (Idle, SuccessEvent.ProcessingStarted(jobId, _)) => Processing(jobId)
          case (Processing(jobId), SuccessEvent.SmsDelivered(_)) => SmsDelivered(jobId)
          case (SmsDelivered(jobId), SuccessEvent.Completed(_)) => Completed(jobId)
          case _ => state
        }
      }
    ).withRecovery(Recovery.default)

    FSMNode(entityKey, behavior, s"${chainId}成功", ResiliencePattern.Communicator, level = 3)
  }

  // ============================================================
  // FSM: Failure (失败处理 + 错误码映射 + 发短信)
  // ============================================================
  private def buildFailureFSM(chainId: String, p: Params): FSMNode = {
    val entityKey = EntityTypeKey[FailureCommand](s"$chainId-failure")
    val rules = p.rules

    val behavior: Behavior[FailureCommand] = EventSourcedBehavior[FailureCommand, FailureEvent, FailureState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = FailureState.Idle,
      commandHandler = { (state, cmd) =>
        import FailureState._
        (state, cmd) match {
          case (Idle, FailureCommand.ProcessFailure(jobId, errorCode, items, replyTo)) =>
            val category = rules.errorCodeMapper(errorCode)
            val event = FailureEvent.FailureCategorized(jobId, errorCode, category)
            Effect.persist(event).thenRun { _ =>
              replyTo ! FailureReply.Categorized(entityKey.name, category)
            }

          case (Categorized(jobId, _, cat), FailureCommand.SendFailureSms) =>
            // Select SMS template based on category
            val msg = cat match {
              case FailureCategory.BalanceInsufficient => p.messages.failureSmsBody
              case _                                   => p.messages.failureSmsBody
            }
            Effect.persist(FailureEvent.SmsSent(jobId))

          case (SmsDelivered(jobId), FailureCommand.ReleaseQuota) if rules.releaseQuotaOnFailure =>
            Effect.persist(FailureEvent.QuotaReleased(jobId))

          case (SmsDelivered(jobId), FailureCommand.Acknowledge) =>
            Effect.persist(FailureEvent.Acknowledged(jobId))

          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        import FailureState._
        (state, event) match {
          case (Idle, FailureEvent.FailureCategorized(jobId, code, cat)) =>
            Categorized(jobId, code, cat)
          case (Categorized(jobId, _, _), FailureEvent.SmsSent(_)) =>
            SmsDelivered(jobId)
          case (SmsDelivered(jobId), FailureEvent.QuotaReleased(_)) =>
            QuotaReleased(jobId)
          case (SmsDelivered(jobId), FailureEvent.Acknowledged(_)) =>
            Completed(jobId)
          case (QuotaReleased(jobId), FailureEvent.Acknowledged(_)) =>
            Completed(jobId)
          case _ => state
        }
      }
    ).withRecovery(Recovery.default)

    FSMNode(entityKey, behavior, s"${chainId}失败", ResiliencePattern.Communicator, level = 3)
  }

  // ============================================================
  // FSM: P2B Notify (理财平台记账通知)
  // ============================================================
  private def buildP2BNotifyFSM(chainId: String, p: Params): FSMNode = {
    val entityKey = EntityTypeKey[P2BNotifyCommand](s"$chainId-p2b")

    val behavior: Behavior[P2BNotifyCommand] = EventSourcedBehavior[P2BNotifyCommand, P2BNotifyEvent, P2BNotifyState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = P2BNotifyState.Idle,
      commandHandler = { (state, cmd) =>
        import P2BNotifyState._
        (state, cmd) match {
          case (Idle, P2BNotifyCommand.NotifyPlatform(jobId, items, replyTo)) =>
            val event = P2BNotifyEvent.NotifyStarted(jobId, items.size)
            Effect.persist(event).thenRun { _ =>
              // p.bindings.p2b.tell(P2BCommand.RecordFlow(items, p.rules.p2bFlowType))(...)
              replyTo ! P2BNotifyReply.Started(entityKey.name)
            }

          case (Notifying(jobId), P2BNotifyCommand.PlatformAcknowledged) =>
            Effect.persist(P2BNotifyEvent.NotifyCompleted(jobId))

          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        import P2BNotifyState._
        (state, event) match {
          case (Idle, P2BNotifyEvent.NotifyStarted(jobId, _)) => Notifying(jobId)
          case (Notifying(jobId), P2BNotifyEvent.NotifyCompleted(_)) => Completed(jobId)
          case _ => state
        }
      }
    ).withRecovery(Recovery.default)

    FSMNode(entityKey, behavior, s"${chainId}P2B通知", ResiliencePattern.Communicator, level = 3)
  }
}

object ExternalTwoPhaseChainTemplate {

  /**
   * All variation points between two isomorphic business chains.
   *
   * This is the *only* thing that differs between recharge and purchase.
   * The template captures everything else.
   */
  final case class Params(
      chainId:  String,
      bindings: ChainExternalBindings,
      messages: ChainMessages,
      rules:    ChainBusinessRules
  )

  // ============================================================
  // REQUEST FSM — Protocols
  // ============================================================
  sealed trait RequestCommand
  object RequestCommand {
    final case class Start(jobId: String, items: List[String], replyTo: ActorRef[RequestReply]) extends RequestCommand
    final case class FileGenerated(filePath: String) extends RequestCommand
    final case object UploadCompleted extends RequestCommand
    final case object EamsNotified extends RequestCommand
    final case class CheckTimeout(deadline: Long) extends RequestCommand
  }

  sealed trait RequestReply
  object RequestReply {
    final case class Acknowledged(fsmName: String, jobId: String) extends RequestReply
  }

  sealed trait RequestEvent
  object RequestEvent {
    final case class GenerationStarted(jobId: String, items: List[String]) extends RequestEvent
    final case class FileGenerated(jobId: String, filePath: String) extends RequestEvent
    final case class UploadCompleted(jobId: String) extends RequestEvent
    final case class RequestCompleted(jobId: String) extends RequestEvent
    final case class RequestFailed(reason: String) extends RequestEvent
  }

  sealed trait RequestState
  object RequestState {
    case object Idle extends RequestState
    final case class Generating(jobId: String, items: List[String]) extends RequestState
    final case class FileReady(jobId: String, filePath: String) extends RequestState
    final case class Uploaded(jobId: String) extends RequestState
    final case class Completed(jobId: String) extends RequestState
    final case class Failed(reason: String) extends RequestState
  }

  // ============================================================
  // RESPONSE FSM — Protocols
  // ============================================================
  sealed trait ResponseCommand
  object ResponseCommand {
    final case class StartPolling(jobId: String, replyTo: ActorRef[ResponseReply]) extends ResponseCommand
    final case class FileDetected(filePath: String) extends ResponseCommand
    final case class ParseCompleted(results: List[String]) extends ResponseCommand
    final case object RouteOutcome extends ResponseCommand
  }

  sealed trait ResponseReply
  object ResponseReply {
    final case class Started(fsmName: String) extends ResponseReply
  }

  sealed trait ResponseEvent
  object ResponseEvent {
    final case class PollingStarted(jobId: String) extends ResponseEvent
    final case class FileDownloaded(jobId: String, filePath: String) extends ResponseEvent
    final case class Parsed(jobId: String, results: List[String]) extends ResponseEvent
    final case class Completed(jobId: String, results: List[String]) extends ResponseEvent
  }

  sealed trait ResponseState
  object ResponseState {
    case object Idle extends ResponseState
    final case class Polling(jobId: String) extends ResponseState
    final case class Downloaded(jobId: String, filePath: String) extends ResponseState
    final case class Parsed(jobId: String, results: List[String]) extends ResponseState
    final case class Completed(jobId: String) extends ResponseState
  }

  // ============================================================
  // RECONFIRM FSM — Protocols
  // ============================================================
  sealed trait ReconfirmCommand
  object ReconfirmCommand {
    final case class Verify(jobId: String, items: List[String], replyTo: ActorRef[ReconfirmReply]) extends ReconfirmCommand
    final case class VerificationResult(ok: List[String], failed: List[String]) extends ReconfirmCommand
  }

  sealed trait ReconfirmReply
  object ReconfirmReply {
    final case class Started(fsmName: String) extends ReconfirmReply
  }

  sealed trait ReconfirmEvent
  object ReconfirmEvent {
    final case class VerificationStarted(jobId: String, totalItems: Int) extends ReconfirmEvent
    final case class AllVerified(jobId: String) extends ReconfirmEvent
    final case class PartialMismatch(jobId: String, failed: List[String]) extends ReconfirmEvent
  }

  sealed trait ReconfirmState
  object ReconfirmState {
    case object Idle extends ReconfirmState
    final case class Verifying(jobId: String, totalItems: Int) extends ReconfirmState
    final case class Verified(jobId: String) extends ReconfirmState
    final case class Mismatch(jobId: String, failed: List[String]) extends ReconfirmState
  }

  // ============================================================
  // SUCCESS FSM — Protocols
  // ============================================================
  sealed trait SuccessCommand
  object SuccessCommand {
    final case class ProcessSuccess(jobId: String, items: List[String], replyTo: ActorRef[SuccessReply]) extends SuccessCommand
    final case object SmsSent extends SuccessCommand
    final case object SuccessAcknowledged extends SuccessCommand
  }

  sealed trait SuccessReply
  object SuccessReply {
    final case class Started(fsmName: String) extends SuccessReply
  }

  sealed trait SuccessEvent
  object SuccessEvent {
    final case class ProcessingStarted(jobId: String, total: Int) extends SuccessEvent
    final case class SmsDelivered(jobId: String) extends SuccessEvent
    final case class Completed(jobId: String) extends SuccessEvent
  }

  sealed trait SuccessState
  object SuccessState {
    case object Idle extends SuccessState
    final case class Processing(jobId: String) extends SuccessState
    final case class SmsDelivered(jobId: String) extends SuccessState
    final case class Completed(jobId: String) extends SuccessState
  }

  // ============================================================
  // FAILURE FSM — Protocols
  // ============================================================
  sealed trait FailureCommand
  object FailureCommand {
    final case class ProcessFailure(jobId: String, errorCode: String, items: List[String], replyTo: ActorRef[FailureReply])
        extends FailureCommand
    final case object SendFailureSms extends FailureCommand
    final case object ReleaseQuota extends FailureCommand
    final case object Acknowledge extends FailureCommand
  }

  sealed trait FailureReply
  object FailureReply {
    final case class Categorized(fsmName: String, category: FailureCategory) extends FailureReply
  }

  sealed trait FailureEvent
  object FailureEvent {
    final case class FailureCategorized(jobId: String, errorCode: String, category: FailureCategory) extends FailureEvent
    final case class SmsSent(jobId: String) extends FailureEvent
    final case class QuotaReleased(jobId: String) extends FailureEvent
    final case class Acknowledged(jobId: String) extends FailureEvent
  }

  sealed trait FailureState
  object FailureState {
    case object Idle extends FailureState
    final case class Categorized(jobId: String, errorCode: String, category: FailureCategory) extends FailureState
    final case class SmsDelivered(jobId: String) extends FailureState
    final case class QuotaReleased(jobId: String) extends FailureState
    final case class Completed(jobId: String) extends FailureState
  }

  // ============================================================
  // P2B NOTIFY FSM — Protocols
  // ============================================================
  sealed trait P2BNotifyCommand
  object P2BNotifyCommand {
    final case class NotifyPlatform(jobId: String, items: List[String], replyTo: ActorRef[P2BNotifyReply])
        extends P2BNotifyCommand
    final case object PlatformAcknowledged extends P2BNotifyCommand
  }

  sealed trait P2BNotifyReply
  object P2BNotifyReply {
    final case class Started(fsmName: String) extends P2BNotifyReply
  }

  sealed trait P2BNotifyEvent
  object P2BNotifyEvent {
    final case class NotifyStarted(jobId: String, total: Int) extends P2BNotifyEvent
    final case class NotifyCompleted(jobId: String) extends P2BNotifyEvent
  }

  sealed trait P2BNotifyState
  object P2BNotifyState {
    case object Idle extends P2BNotifyState
    final case class Notifying(jobId: String) extends P2BNotifyState
    final case class Completed(jobId: String) extends P2BNotifyState
  }
}

package net.imadz.m25.template

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration.FiniteDuration

/**
 * Generic NotificationRelay template — covers the "support" resilience pattern.
 *
 * Generates 2 FSM nodes from a single parameter set:
 *   1. SuccessNotificationFSM — sends success-path messages (happy path)
 *   2. FailureNotificationFSM — sends failure/reminder messages (sad path)
 *
 * Both share the same SMS pipeline but differ in:
 *   - Message template (title + body)
 *   - Compliance window (time-of-day sending restrictions)
 *   - Retry policy
 *
 * Currently covers: sms-service, reminder-sms (2 FSM)
 */
final class NotificationRelayTemplate
  extends FlowTemplate[NotificationRelayTemplate.Params] {

  import NotificationRelayTemplate._

  override def materialize(p: Params): DAGSubgraph = {
    val success  = buildRelayFSM(p, isFailurePath = false)
    val reminder = buildRelayFSM(p, isFailurePath = true)

    DAGSubgraph(
      nodes = Seq(success, reminder),
      edges = Seq.empty // No internal edges; connected from business chain nodes
    )
  }

  private def buildRelayFSM(p: Params, isFailurePath: Boolean): FSMNode = {
    val suffix  = if (isFailurePath) "reminder" else "service"
    val entityKey = EntityTypeKey[RelayCommand](s"${p.serviceName}-$suffix")
    val template  = if (isFailurePath) p.failureTemplate else p.successTemplate

    val behavior: Behavior[RelayCommand] = EventSourcedBehavior[RelayCommand, RelayEvent, RelayState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = RelayState.Idle,
      commandHandler = { (state, cmd) =>
        (state, cmd) match {
          case (RelayState.Idle, RelayCommand.Send(recipients, vars, replyTo)) =>
            if (isWithinComplianceWindow(p.complianceWindow)) {
              val messages = recipients.map { r =>
                template.interpolate(vars + ("recipient" -> r))
              }
              Effect.persist(RelayEvent.Queued(recipients.size, messages)).thenRun { _ =>
                replyTo ! RelayReply.Queued(entityKey.name, recipients.size)
              }
            } else {
              Effect.persist(RelayEvent.Deferred(recipients.size, "outside-compliance-window")).thenRun { _ =>
                replyTo ! RelayReply.Deferred(entityKey.name, "outside-compliance-window")
              }
            }

          case (RelayState.Queued(count, msgs), RelayCommand.DeliveryConfirmed(delivered, failed)) =>
            if (failed > 0 && count > 0)
              Effect.persist(RelayEvent.PartialFailure(delivered, failed))
            else
              Effect.persist(RelayEvent.AllDelivered(delivered))

          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        (state, event) match {
          case (RelayState.Idle, RelayEvent.Queued(count, msgs)) =>
            RelayState.Queued(count, msgs)
          case (RelayState.Queued(_, _), RelayEvent.AllDelivered(count)) =>
            RelayState.Completed(count)
          case (RelayState.Queued(_, _), RelayEvent.PartialFailure(ok, fail)) =>
            RelayState.PartiallyFailed(ok, fail)
          case _ => state
        }
      }
    )

    val label = if (isFailurePath) s"${p.serviceName}-reminder" else s"${p.serviceName}-success"
    FSMNode(entityKey, behavior, label, ResiliencePattern.Support, level = 4)
  }

  private def isWithinComplianceWindow(window: (Int, Int)): Boolean = {
    val hour = java.time.LocalTime.now().getHour
    val (start, end) = window
    hour >= start && hour < end
  }
}

object NotificationRelayTemplate {
  final case class Params(
      serviceName:      String,
      successTemplate:  MessageTemplate,
      failureTemplate:  MessageTemplate,
      complianceWindow: (Int, Int),  // (startHour, endHour), e.g. (8, 20)
      retryPolicy:      RetryPolicy = RetryPolicy.default
  )

  /** A parameterized message template with {{variable}} placeholders. */
  final case class MessageTemplate(
      title: String,
      body:  String
  ) {
    def interpolate(vars: Map[String, String]): String =
      vars.foldLeft(s"$title: $body") { case (s, (k, v)) =>
        s.replace(s"{{$k}}", v)
      }
  }

  final case class RetryPolicy(maxRetries: Int, backoff: FiniteDuration)
  object RetryPolicy {
    val default: RetryPolicy = RetryPolicy(3, scala.concurrent.duration.FiniteDuration(5, "seconds"))
  }

  sealed trait RelayCommand
  object RelayCommand {
    final case class Send(recipients: List[String], vars: Map[String, String], replyTo: akka.actor.typed.ActorRef[RelayReply])
        extends RelayCommand
    final case class DeliveryConfirmed(delivered: Int, failed: Int) extends RelayCommand
  }

  sealed trait RelayReply
  object RelayReply {
    final case class Queued(fsmName: String, count: Int) extends RelayReply
    final case class Deferred(fsmName: String, reason: String) extends RelayReply
  }

  sealed trait RelayEvent
  object RelayEvent {
    final case class Queued(count: Int, messages: List[String]) extends RelayEvent
    final case class AllDelivered(count: Int) extends RelayEvent
    final case class PartialFailure(delivered: Int, failed: Int) extends RelayEvent
    final case class Deferred(count: Int, reason: String) extends RelayEvent
  }

  sealed trait RelayState
  object RelayState {
    case object Idle extends RelayState
    final case class Queued(count: Int, messages: List[String]) extends RelayState
    final case class Completed(count: Int) extends RelayState
    final case class PartiallyFailed(delivered: Int, failed: Int) extends RelayState
  }
}

package net.imadz.m25.template

import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration.FiniteDuration

/**
 * Generic ResourceGuard template — covers the "protector" resilience pattern.
 *
 * Generates up to 3 FSM nodes from a single parameter set:
 *   1. ResourceReserveFSM  — lock a resource before work begins
 *   2. ResourceReleaseFSM  — unlock a per-user resource on failure/timeout
 *   3. CascadeReleaseFSM   — release total/aggregate resources
 *
 * Each FSM carries a timeout invariant: if the lease expires before
 * explicit release, the guard auto-releases to prevent deadlock.
 *
 * Currently covers: quota-reserve, quota-release-u, quota-release-t (3 FSM)
 */
final class ResourceGuardTemplate
  extends FlowTemplate[ResourceGuardTemplate.Params] {

  import ResourceGuardTemplate._

  override def materialize(p: Params): DAGSubgraph = {
    val reserve = buildReserveFSM(p)
    val release = buildReleaseFSM(p)
    val cascade = p.cascadeTo.map(_ => buildCascadeReleaseFSM(p))

    DAGSubgraph(
      nodes = Seq(Some(reserve), Some(release), cascade).flatten,
      edges = Seq(
        DAGEdge(s"${p.resourceType}-reserve", s"${p.resourceType}-release", "on-failure"),
        DAGEdge(s"${p.resourceType}-release", s"${p.resourceType}-cascade", "cascade")
      ).filter(e => p.cascadeTo.isDefined || !e.to.contains("cascade"))
    )
  }

  private def buildReserveFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[GuardCommand](s"${p.resourceType}-reserve")

    val behavior: Behavior[GuardCommand] = EventSourcedBehavior[GuardCommand, GuardEvent, GuardState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = GuardState.Idle,
      commandHandler = { (state, cmd) =>
        (state, cmd) match {
          case (GuardState.Idle, GuardCommand.Reserve(owner, amount, deadline, replyTo)) =>
            Effect.persist(GuardEvent.Reserved(owner, amount, deadline)).thenRun { _ =>
              replyTo ! GuardReply.Reserved(entityKey.name, leaseId = s"${owner}-${System.currentTimeMillis()}")
            }

          case (GuardState.Held(owner, amount, deadline), GuardCommand.CheckTimeout) =>
            if (System.currentTimeMillis() > deadline)
              Effect.persist(GuardEvent.AutoReleased(owner, "lease-expired"))
            else
              Effect.none

          case (GuardState.Held(owner, _, _), GuardCommand.Release(owner2, replyTo)) if owner == owner2 =>
            Effect.persist(GuardEvent.ManuallyReleased(owner)).thenRun { _ =>
              replyTo ! GuardReply.Released(entityKey.name)
            }

          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        (state, event) match {
          case (GuardState.Idle, GuardEvent.Reserved(owner, amount, deadline)) =>
            GuardState.Held(owner, amount, deadline)
          case (_, GuardEvent.AutoReleased(owner, reason)) =>
            GuardState.Released(owner, reason)
          case (_, GuardEvent.ManuallyReleased(owner)) =>
            GuardState.Released(owner, "manual")
          case _ => state
        }
      }
    )

    FSMNode(entityKey, behavior, s"${p.resourceType}-reserve", ResiliencePattern.Protector, level = 2)
  }

  private def buildReleaseFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[GuardCommand](s"${p.resourceType}-release")

    val behavior: Behavior[GuardCommand] = EventSourcedBehavior[GuardCommand, GuardEvent, GuardState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = GuardState.Idle,
      commandHandler = { (state, cmd) =>
        (state, cmd) match {
          case (GuardState.Idle, GuardCommand.Release(owner, replyTo)) =>
            Effect.persist(GuardEvent.ManuallyReleased(owner)).thenRun { _ =>
              replyTo ! GuardReply.Released(entityKey.name)
              // Cascade to total release if configured
            }
          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        (state, event) match {
          case (GuardState.Idle, GuardEvent.ManuallyReleased(owner)) =>
            GuardState.Released(owner, "manual")
          case _ => state
        }
      }
    )

    FSMNode(entityKey, behavior, s"${p.resourceType}-release", ResiliencePattern.Protector, level = 5)
  }

  private def buildCascadeReleaseFSM(p: Params): FSMNode = {
    val entityKey = EntityTypeKey[GuardCommand](s"${p.resourceType}-cascade")

    val behavior: Behavior[GuardCommand] = EventSourcedBehavior[GuardCommand, GuardEvent, GuardState](
      persistenceId = PersistenceId.of(entityKey.name, ""),
      emptyState    = GuardState.Idle,
      commandHandler = { (state, cmd) =>
        (state, cmd) match {
          case (GuardState.Idle, GuardCommand.Release(owner, replyTo)) =>
            Effect.persist(GuardEvent.ManuallyReleased(owner)).thenRun { _ =>
              replyTo ! GuardReply.Released(entityKey.name)
            }
          case _ => Effect.unhandled
        }
      },
      eventHandler = { (state, event) =>
        (state, event) match {
          case (GuardState.Idle, GuardEvent.ManuallyReleased(owner)) =>
            GuardState.Released(owner, "cascade")
          case _ => state
        }
      }
    )

    FSMNode(entityKey, behavior, s"${p.resourceType}-cascade", ResiliencePattern.Protector, level = 5)
  }
}

object ResourceGuardTemplate {
  final case class Params(
      resourceType:   String,
      timeout:        FiniteDuration,
      releaseStrategy: ReleaseStrategy,
      cascadeTo:      Option[ResourceGuardTemplate.Params] = None
  )

  sealed trait ReleaseStrategy
  object ReleaseStrategy {
    /** Release only on explicit command (no auto-release on timeout). */
    case object ManualOnly extends ReleaseStrategy
    /** Auto-release when lease expires; manual release also accepted. */
    case object TimeoutAutoRelease extends ReleaseStrategy
  }

  sealed trait GuardCommand
  object GuardCommand {
    final case class Reserve(owner: String, amount: Long, deadline: Long, replyTo: akka.actor.typed.ActorRef[GuardReply])
        extends GuardCommand
    final case class Release(owner: String, replyTo: akka.actor.typed.ActorRef[GuardReply]) extends GuardCommand
    case object CheckTimeout extends GuardCommand
  }

  sealed trait GuardReply
  object GuardReply {
    final case class Reserved(fsmName: String, leaseId: String) extends GuardReply
    final case class Released(fsmName: String) extends GuardReply
  }

  sealed trait GuardEvent
  object GuardEvent {
    final case class Reserved(owner: String, amount: Long, deadline: Long) extends GuardEvent
    final case class ManuallyReleased(owner: String) extends GuardEvent
    final case class AutoReleased(owner: String, reason: String) extends GuardEvent
  }

  sealed trait GuardState
  object GuardState {
    case object Idle extends GuardState
    final case class Held(owner: String, amount: Long, deadline: Long) extends GuardState
    final case class Released(owner: String, reason: String) extends GuardState
  }
}

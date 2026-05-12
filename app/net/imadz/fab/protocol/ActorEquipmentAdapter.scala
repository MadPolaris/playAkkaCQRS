package net.imadz.fab.protocol

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Akka Typed Actor-based equipment adapter — in-process simulation transport.
 *
 * Each equipment simulator is an Akka Typed Actor. This adapter looks up
 * (or is injected with) actor references and communicates via ask pattern.
 *
 * This is the default transport for the Fab simulation demo.
 * For real equipment, replace with GrpcEquipmentAdapter.
 */
class ActorEquipmentAdapter(implicit
  system: ActorSystem[Nothing],
  ec: ExecutionContext
) extends EquipmentAdapter[Future] {

  override def adapterId: String = "akka-actor-inprocess"

  private implicit val timeout: Timeout = 30.seconds

  // Registered simulator actors: equipmentId → ActorRef
  private var simulators: Map[String, ActorRef[SimulatorCommand]] = Map.empty
  private var subscribers: Map[String, List[EquipmentEvent => Unit]] = Map.empty

  /**
   * Register a simulator actor for a given equipment ID.
   * Called during simulation bootstrap.
   */
  def registerSimulator(equipmentId: String, ref: ActorRef[SimulatorCommand]): Unit = {
    simulators += equipmentId -> ref
    // Also subscribe to unsolicited events from this simulator
    subscribers.get(equipmentId).foreach { callbacks =>
      // The simulator will push events through the adapter
    }
  }

  override def sendCommand(equipmentId: String, cmd: EquipmentCommand): Future[EquipmentEvent] = {
    simulators.get(equipmentId) match {
      case Some(ref) =>
        ref.ask[EquipmentEvent](replyTo => SimulateCommand(cmd, replyTo))
      case None =>
        Future.failed(new IllegalArgumentException(
          s"No simulator registered for equipment '$equipmentId'. Known: ${simulators.keys.mkString(", ")}"
        ))
    }
  }

  override def queryStatus(equipmentId: String): Future[StatusReport] = {
    simulators.get(equipmentId) match {
      case Some(ref) =>
        ref.ask[EquipmentEvent](replyTo => SimulateCommand(QueryStatus(), replyTo))
          .map {
            case s: StatusReport => s
            case other => StatusReport(equipmentId, Error, None, Map.empty)
          }
      case None =>
        Future.successful(StatusReport(equipmentId, Error, None, Map.empty))
    }
  }

  override def subscribe(equipmentId: String)(callback: EquipmentEvent => Unit): Unit = {
    val current = subscribers.getOrElse(equipmentId, Nil)
    subscribers = subscribers + (equipmentId -> (callback :: current))
  }

  override def unsubscribe(equipmentId: String): Unit = {
    subscribers = subscribers - equipmentId
  }

  /** Notify all subscribers about an event from a given equipment */
  private[protocol] def notifySubscribers(equipmentId: String, event: EquipmentEvent): Unit = {
    subscribers.getOrElse(equipmentId, Nil).foreach(cb => cb(event))
  }
}

/**
 * Marker trait for commands accepted by equipment simulator actors.
 * NOT sealed — simulators define their own internal messages (e.g. timer ticks).
 */
trait SimulatorCommand

/** Wrapper command sent to simulator actors. */
case class SimulateCommand(cmd: EquipmentCommand, replyTo: ActorRef[EquipmentEvent]) extends SimulatorCommand

/** Internal timer tick — used by simulators with Akka Behaviors.withTimers */
case object TimerTick extends SimulatorCommand

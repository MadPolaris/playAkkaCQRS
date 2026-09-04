package net.imadz.fab.simulation

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import net.imadz.fab.protocol._
import scala.concurrent.duration._
import scala.util.Random
import java.util.UUID

/**
 * Abstract base for equipment simulator actors.
 *
 * Each simulator models idle → busy → idle state machine with Akka Timers.
 * Processing completes when a Tick timer fires after the configured duration.
 * Subclasses implement `generateResult` to produce equipment-specific output.
 */
abstract class EquipmentSimulator {

  protected val rng: Random = new Random()

  protected case class Job(
    jobId: String,
    recipeId: String,
    replyTo: akka.actor.typed.ActorRef[EquipmentEvent]
  )

  protected case class SimState(
    equipmentId: String,
    status: EquipmentStatus,
    currentJob: Option[Job] = None,
    portOccupancy: Map[String, Option[String]] = Map.empty,
    totalProcessed: Int = 0,
    totalFailed: Int = 0,
    speedMultiplier: Double = 1.0,
    pendingFault: Option[String] = None  // M3.5: force next TimerTick to fail with this fault type
  )

  // ---- Public API ----

  def apply(config: EquipmentConfig): Behavior[SimulatorCommand] =
    Behaviors.withTimers { timers =>
      idle(SimState(config.equipmentId, Idle), config, timers)
    }

  // ---- Abstract — subclass provides result generation ----

  /** Generate the equipment-specific result for a completed job */
  protected def generateResult(state: SimState, job: Job, config: EquipmentConfig): EquipmentResult

  // ---- Idle ----

  private def idle(
    state: SimState, config: EquipmentConfig, timers: TimerScheduler[SimulatorCommand]
  ): Behavior[SimulatorCommand] = Behaviors.receiveMessage {
    case SimulateCommand(cmd: ProcessRecipe, replyTo) =>
      val job = Job(newJobId(), cmd.recipeId, replyTo)
      val scaledTime = scale(config.processingTime, state.speedMultiplier)
      timers.startSingleTimer(TimerTick, scaledTime)
      busy(state.copy(status = Busy, currentJob = Some(job)), config, timers)

    case SimulateCommand(LoadFoup(foupId, portId, _), replyTo) =>
      replyTo ! FoupArrived(foupId, portId)
      idle(state.copy(portOccupancy = state.portOccupancy + (portId -> Some(foupId))), config, timers)

    case SimulateCommand(UnloadFoup(foupId, portId), replyTo) =>
      replyTo ! FoupDeparted(foupId, portId)
      idle(state.copy(portOccupancy = state.portOccupancy + (portId -> None)), config, timers)

    case SimulateCommand(QueryStatus(), replyTo) =>
      replyTo ! StatusReport(state.equipmentId, state.status, None, state.portOccupancy)
      Behaviors.same

    case InjectFault(faultType, _) =>
      idle(state.copy(pendingFault = Some(faultType)), config, timers)

    case SimulateCommand(_, replyTo) =>
      replyTo ! StatusReport(state.equipmentId, Idle, None, state.portOccupancy)
      Behaviors.same
  }

  // ---- Busy ----

  private def busy(
    state: SimState, config: EquipmentConfig, timers: TimerScheduler[SimulatorCommand]
  ): Behavior[SimulatorCommand] = Behaviors.receiveMessage {
    case TimerTick =>
      val faultToInject = state.pendingFault.orElse(drawSpontaneousFault(config))
      faultToInject match {
        case Some(ft) =>
          state.currentJob.foreach { job =>
            job.replyTo ! JobFailed(job.jobId, state.equipmentId, ft, s"Simulated fault: $ft")
          }
          idle(
            state.copy(status = Idle, currentJob = None, totalFailed = state.totalFailed + 1, pendingFault = None),
            config, timers
          )
        case None =>
          completeJob(state, config, timers)
      }

    case InjectFault(faultType, _) =>
      busy(state.copy(pendingFault = Some(faultType)), config, timers)

    case SimulateCommand(AbortJob(jobId, reason), replyTo) =>
      timers.cancel(TimerTick)
      replyTo ! JobFailed(jobId, state.equipmentId, "ABORTED", reason)
      idle(state.copy(status = Idle, currentJob = None, totalFailed = state.totalFailed + 1), config, timers)

    case SimulateCommand(QueryStatus(), replyTo) =>
      replyTo ! StatusReport(state.equipmentId, Busy, state.currentJob.map(_.jobId), state.portOccupancy)
      Behaviors.same

    case _ => Behaviors.same
  }

  // ---- Job Completion ----

  private def completeJob(
    state: SimState, config: EquipmentConfig, timers: TimerScheduler[SimulatorCommand]
  ): Behavior[SimulatorCommand] = {
    state.currentJob match {
      case Some(job) =>
        val result = generateResult(state, job, config)
        job.replyTo ! JobCompleted(job.jobId, state.equipmentId, result)
        idle(
          state.copy(status = Idle, currentJob = None, totalProcessed = state.totalProcessed + 1),
          config, timers
        )
      case None =>
        idle(state, config, timers)
    }
  }

  /** M3.5: Randomly decide whether to inject a spontaneous fault based on config.faultProbability. */
  private def drawSpontaneousFault(config: EquipmentConfig): Option[String] = {
    if (config.faultProbability > 0.0 && rng.nextDouble() < config.faultProbability)
      Some(FaultType.all(rng.nextInt(FaultType.all.size)))
    else None
  }

  protected def newJobId(): String = UUID.randomUUID().toString

  protected def scale(d: FiniteDuration, multiplier: Double): FiniteDuration =
    if (multiplier > 0) (d.toMillis / multiplier).millis else d
}

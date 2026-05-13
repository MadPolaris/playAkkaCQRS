package net.imadz.domain.entities

import net.imadz.common.CommonTypes.Id
import net.imadz.common.CborSerializable

object LotEntity {

  // @formatter:off
  // State
  case class LotState(
    lotId: Id,
    productId: String,
    waferIds: Set[Id],                  // currently owned wafers
    reservedWafers: Map[Id, Set[Id]],   // outgoing: transferId -> waferIds being removed
    incomingWafers: Map[Id, Set[Id]],   // incoming: transferId -> waferIds being added
    phase: LotPhase,
    completedTransferIds: Set[Id] = Set.empty // committed transfer ids for idempotency
  )

  def empty(lotId: Id): LotState = LotState(lotId, "", Set.empty, Map.empty, Map.empty, Empty)

  // Phase state machine
  sealed trait LotPhase extends CborSerializable
  case object Empty extends LotPhase
  case object Active extends LotPhase
  case object Sealed extends LotPhase
  case object Completed extends LotPhase

  // Event
  sealed trait LotEvent extends CborSerializable
  case class LotCreated(productId: String, waferIds: Set[Id]) extends LotEvent
  case class WaferRemovalReserved(transferId: Id, waferIds: Set[Id]) extends LotEvent
  case class WaferRemovalCommitted(transferId: Id) extends LotEvent
  case class WaferRemovalReleased(transferId: Id) extends LotEvent
  case class WaferAdditionReserved(transferId: Id, waferIds: Set[Id]) extends LotEvent
  case class WaferAdditionCommitted(transferId: Id) extends LotEvent
  case class WaferAdditionCanceled(transferId: Id) extends LotEvent
  case class PhaseStarted(phaseId: String) extends LotEvent
  case class PhaseCompleted(phaseId: String) extends LotEvent
  case class LotSealed() extends LotEvent
  // @formatter:on

  // Event Handler Extension Point
  type LotEventHandler = (LotState, LotEvent) => LotState

}

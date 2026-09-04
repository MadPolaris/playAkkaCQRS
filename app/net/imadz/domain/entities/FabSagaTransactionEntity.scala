package net.imadz.domain.entities

import net.imadz.common.CborSerializable
import net.imadz.common.CommonTypes.Id

object FabSagaTransactionEntity {

  // --- State ---
  sealed trait Status extends CborSerializable
  object Status {
    case object New extends Status
    case object Initiated extends Status
    case object Completed extends Status
    case class Failed(reason: String) extends Status
  }

  case class FabSagaTransactionState(
    id: Option[String] = None,
    sourceLotId: Option[Id] = None,
    targetLotId: Option[Id] = None,
    waferIds: Set[Id] = Set.empty,
    status: Status = Status.New
  ) extends CborSerializable {

    def applyEvent(event: FabSagaTransactionEvent): FabSagaTransactionState = event match {
      case TransactionInitiated(src, tgt, wafers, _) =>
        copy(sourceLotId = Some(src), targetLotId = Some(tgt), waferIds = wafers, status = Status.Initiated)
      case TransactionCompleted(_, _) =>
        copy(status = Status.Completed)
      case TransactionFailed(_, reason, _) =>
        copy(status = Status.Failed(reason))
    }
  }

  // --- Events ---
  sealed trait FabSagaTransactionEvent extends CborSerializable
  case class TransactionInitiated(sourceLotId: Id, targetLotId: Id, waferIds: Set[Id], timestamp: Long) extends FabSagaTransactionEvent
  case class TransactionCompleted(id: String, timestamp: Long) extends FabSagaTransactionEvent
  case class TransactionFailed(id: String, reason: String, timestamp: Long) extends FabSagaTransactionEvent
}

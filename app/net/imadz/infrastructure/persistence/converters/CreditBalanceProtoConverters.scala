package net.imadz.infrastructure.persistence.converters

import net.imadz.common.serialization.PrimitiveConverter
import net.imadz.domain.entities.CreditBalanceEntity._
import net.imadz.domain.values.Money
// 根据之前的约定，假设 ScalaPB 生成的代码位于 snake_case 的包下
// 如果您的实际生成包是 net.imadz.banking.infrastructure.proto，请调整此处 import
import net.imadz.infrastructure.proto.credits._

trait CreditBalanceProtoConverters extends PrimitiveConverter {

  // --- Value Objects ---

  object MoneyConv extends ProtoConverter[Money, MoneyPO] {
    override def toProto(m: Money): MoneyPO = MoneyPO(
      amount = m.amount.doubleValue,
      currency = CurrencyConv.toProto(m.currency)
    )

    override def fromProto(p: MoneyPO): Money = Money(
      BigDecimal(p.amount),
      CurrencyConv.fromProto(p.currency)
    )
  }

  // --- Events ---

  object BalanceChangedConv extends ProtoConverter[BalanceChanged, BalanceChangedPO] {
    override def toProto(e: BalanceChanged): BalanceChangedPO = BalanceChangedPO(
      update = Some(MoneyConv.toProto(e.update)),
      timestamp = e.timestamp
    )

    override def fromProto(p: BalanceChangedPO): BalanceChanged = BalanceChanged(
      update = MoneyConv.fromProto(p.update.getOrElse(throw new IllegalArgumentException("Missing update"))),
      timestamp = p.timestamp
    )
  }

  object FundsReservedConv extends ProtoConverter[FundsReserved, FundsReservedPO] {
    override def toProto(e: FundsReserved): FundsReservedPO = FundsReservedPO(
      transferId = IdConv.toProto(e.transferId),
      amount = Some(MoneyConv.toProto(e.amount))
    )

    override def fromProto(p: FundsReservedPO): FundsReserved = FundsReserved(
      transferId = IdConv.fromProto(p.transferId),
      amount = MoneyConv.fromProto(p.amount.getOrElse(throw new IllegalArgumentException("Missing amount")))
    )
  }

  // [补全] FundsDeducted
  object FundsDeductedConv extends ProtoConverter[FundsDeducted, FundsDeductedPO] {
    override def toProto(e: FundsDeducted): FundsDeductedPO = FundsDeductedPO(
      transferId = IdConv.toProto(e.transferId),
      amount = Some(MoneyConv.toProto(e.amount))
    )

    override def fromProto(p: FundsDeductedPO): FundsDeducted = FundsDeducted(
      transferId = IdConv.fromProto(p.transferId),
      amount = MoneyConv.fromProto(p.amount.getOrElse(throw new IllegalArgumentException("Missing amount")))
    )
  }

  // [补全] ReservationReleased
  object ReservationReleasedConv extends ProtoConverter[ReservationReleased, ReservationReleasedPO] {
    override def toProto(e: ReservationReleased): ReservationReleasedPO = ReservationReleasedPO(
      transferId = IdConv.toProto(e.transferId),
      amount = Some(MoneyConv.toProto(e.amount))
    )

    override def fromProto(p: ReservationReleasedPO): ReservationReleased = ReservationReleased(
      transferId = IdConv.fromProto(p.transferId),
      amount = MoneyConv.fromProto(p.amount.getOrElse(throw new IllegalArgumentException("Missing amount")))
    )
  }

  // [补全] IncomingCreditsRecorded
  object IncomingCreditsRecordedConv extends ProtoConverter[IncomingCreditsRecorded, IncomingCreditsRecordedPO] {
    override def toProto(e: IncomingCreditsRecorded): IncomingCreditsRecordedPO = IncomingCreditsRecordedPO(
      transferId = IdConv.toProto(e.transferId),
      amount = Some(MoneyConv.toProto(e.amount))
    )

    override def fromProto(p: IncomingCreditsRecordedPO): IncomingCreditsRecorded = IncomingCreditsRecorded(
      transferId = IdConv.fromProto(p.transferId),
      amount = MoneyConv.fromProto(p.amount.getOrElse(throw new IllegalArgumentException("Missing amount")))
    )
  }

  // [补全] IncomingCreditsCommited (注意拼写与 Entity/Proto 保持一致: Commited)
  object IncomingCreditsCommitedConv extends ProtoConverter[IncomingCreditsCommited, IncomingCreditsCommitedPO] {
    override def toProto(e: IncomingCreditsCommited): IncomingCreditsCommitedPO = IncomingCreditsCommitedPO(
      transferId = IdConv.toProto(e.transferId)
    )

    override def fromProto(p: IncomingCreditsCommitedPO): IncomingCreditsCommited = IncomingCreditsCommited(
      transferId = IdConv.fromProto(p.transferId)
    )
  }

  // [补全] IncomingCreditsCanceled
  object IncomingCreditsCanceledConv extends ProtoConverter[IncomingCreditsCanceled, IncomingCreditsCanceledPO] {
    override def toProto(e: IncomingCreditsCanceled): IncomingCreditsCanceledPO = IncomingCreditsCanceledPO(
      transferId = IdConv.toProto(e.transferId)
    )

    override def fromProto(p: IncomingCreditsCanceledPO): IncomingCreditsCanceled = IncomingCreditsCanceled(
      transferId = IdConv.fromProto(p.transferId)
    )
  }

  // --- State Snapshot ---

  object CreditBalanceStateConv extends ProtoConverter[CreditBalanceState, CreditBalanceStatePO] {
    override def toProto(s: CreditBalanceState): CreditBalanceStatePO = CreditBalanceStatePO(
      userId = IdConv.toProto(s.userId),
      // 1. Map[String, Money] -> 使用 StringConv + MoneyConv
      accountBalance = toProtoMap(s.accountBalance, StringConv, MoneyConv),
      // 2. Map[Id, Money] -> 使用 IdConv + MoneyConv
      reservedAmount = toProtoMap(s.reservedAmount, IdConv, MoneyConv),
      // 3. Map[Id, Money] -> 使用 IdConv + MoneyConv
      incomingCredits = toProtoMap(s.incomingCredits, IdConv, MoneyConv)
    )

    override def fromProto(p: CreditBalanceStatePO): CreditBalanceState = CreditBalanceState(
      userId = IdConv.fromProto(p.userId),
      // 这里的逻辑是对称的
      accountBalance = fromProtoMap(p.accountBalance, StringConv, MoneyConv),
      reservedAmount = fromProtoMap(p.reservedAmount, IdConv, MoneyConv),
      incomingCredits = fromProtoMap(p.incomingCredits, IdConv, MoneyConv)
    )
  }
}
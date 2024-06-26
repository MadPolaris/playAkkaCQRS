package net.imadz.domain.services

import net.imadz.common.CommonTypes.{DomainService, iMadzError}
import net.imadz.domain.values.Money

object TransferDomainService extends DomainService {
  def validateTransfer(fromBalance: Money, transferAmount: Money): Either[iMadzError, Unit] = {
    if (fromBalance.amount < transferAmount.amount) {
      Left(iMadzError("60003", "Insufficient balance for transfer"))
    } else if (transferAmount.amount <= BigDecimal(0)) {
      Left(iMadzError("60004", "Transfer amount must be positive"))
    } else {
      Right(())
    }
  }
}
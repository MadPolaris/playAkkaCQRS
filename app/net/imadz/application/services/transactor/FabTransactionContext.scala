package net.imadz.application.services.transactor

import net.imadz.application.aggregates.repository.LotRepository
import net.imadz.application.aggregates.repository.WaferRepository

case class FabTransactionContext(
  lotRepository: LotRepository,
  waferRepository: WaferRepository
)

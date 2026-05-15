package net.imadz.application.services.transactor

import net.imadz.application.aggregates.repository.LotRepository

case class FabTransactionContext(
  lotRepository: LotRepository
)

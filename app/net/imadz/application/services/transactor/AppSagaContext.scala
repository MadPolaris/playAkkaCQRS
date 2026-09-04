package net.imadz.application.services.transactor

import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.aggregates.repository.LotRepository

/** Shared execution context for ALL sagas running on this node's v3 engine.
  * The engine binds one context per coordinator pool and hands it to every phase
  * invocation: money-transfer reads `creditBalances`, fab-saga reads `lots`. */
final case class AppSagaContext(creditBalances: CreditBalanceRepository, lots: LotRepository)

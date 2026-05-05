package net.imadz.application.projection

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.projection.eventsourced
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.CreditBalanceEntity.{BalanceChanged, FundsDeducted, FundsReserved, IncomingCreditsCanceled, IncomingCreditsCommited, IncomingCreditsRecorded, ReservationReleased}
import net.imadz.infrastructure.persistence.CreditBalanceEventAdapter
import net.imadz.infrastructure.proto.credits.{CreditBalanceEventPO => CreditEventPO}
import org.slf4j.LoggerFactory

import java.time.{Instant, LocalDateTime, ZoneId}

case class MonthlyIncomeAndExpenseSummaryProjectionHandler(sharding: ClusterSharding,
                                                           repository: MonthlyIncomeAndExpenseSummaryRepository) extends JdbcHandler[eventsourced.EventEnvelope[CreditEventPO.Event], ScalikeJdbcSession] {
  private val logger = LoggerFactory.getLogger(getClass)
  val adapter = new CreditBalanceEventAdapter

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[CreditEventPO.Event]): Unit = {
    adapter.fromJournal(envelope.event, "").events.foreach { event =>
      val (year, month, day) = getDateFromTimestamp(envelope.timestamp)
      val userId = envelope.persistenceId.split("\\|").last

      event match {
        case BalanceChanged(update, _) =>
          logger.info(s"Processing BalanceChanged for $userId: ${update.amount}")
          if (update.amount > 0)
            repository.updateIncome(userId, update.amount, year, month, day)
          else {
            repository.updateExpense(userId, -update.amount, year, month, day)
          }

        case FundsReserved(transferId, amount) =>
          logger.info(s"Processing FundsReserved for $userId: ${amount.amount} (Transfer: $transferId)")
          repository.updateExpense(userId, amount.amount, year, month, day)

        case FundsDeducted(transferId, amount) =>
          logger.info(s"Ignoring FundsDeducted for $userId (Transfer: $transferId) - already accounted in FundsReserved")
          ()

        case ReservationReleased(transferId, amount) =>
          logger.info(s"Processing ReservationReleased for $userId: ${amount.amount} (Transfer: $transferId)")
          repository.updateExpense(userId, -amount.amount, year, month, day)

        case IncomingCreditsRecorded(transferId, amount) =>
          logger.info(s"Processing IncomingCreditsRecorded for $userId: ${amount.amount} (Transfer: $transferId)")
          // IncomingCreditsRecorded represents expected income
          repository.updateIncome(userId, amount.amount, year, month, day)

        case IncomingCreditsCommited(transferId) =>
          logger.info(s"Processing IncomingCreditsCommited for $userId (Transfer: $transferId)")
          ()

        case IncomingCreditsCanceled(transferId) =>
          logger.info(s"Processing IncomingCreditsCanceled for $userId (Transfer: $transferId)")
          // We need the amount to undo the income. Since IncomingCreditsCanceled doesn't have amount, 
          // this is a design limitation. But for now we just log it.
          // Wait, the event definition in CreditBalanceEntity:
          // case class IncomingCreditsCanceled(transferId: Id) extends CreditBalanceEvent
          // It doesn't have amount.
          ()

        case e =>
          logger.debug(s"Ignoring event for projection: ${e.getClass.getSimpleName}")
      }
    }
  }


  private def getDateFromTimestamp(timestamp: Long): (Int, Int, Int) = {
    // 修复：如果时间戳为 0（代表未设置），使用当前系统时间作为兜底，避免产生 1970 记录
    val effectiveTimestamp = if (timestamp <= 0) System.currentTimeMillis() else timestamp
    val instant = Instant.ofEpochMilli(effectiveTimestamp)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    val year = dateTime.getYear
    val month = dateTime.getMonthValue
    val day = dateTime.getDayOfMonth
    (year, month, day)
  }
}

package net.imadz.application.projection

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.projection.eventsourced
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.domain.entities.CreditBalanceEntity.{BalanceChanged, CreditBalanceEvent}

import java.time.{Instant, LocalDateTime, ZoneId}

case class MonthlyIncomeAndExpenseSummaryProjectionHandler(sharding: ClusterSharding,
                                                           repository: MonthlyIncomeAndExpenseSummaryRepository) extends JdbcHandler[eventsourced.EventEnvelope[CreditBalanceEvent], ScalikeJdbcSession] {

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[CreditBalanceEvent]): Unit = {
    envelope.event match {
      case BalanceChanged(update, timestamp) =>
        val (year, month, day) = getDateFromTimestamp(timestamp)
        val userId = envelope.persistenceId
        if (update.amount > 0)
          repository.updateIncome(userId, update.amount, year, month, day)
        else
          repository.updateExpense(userId, update.amount, year, month, day)
    }
  }

  def getDateFromTimestamp(timestamp: Long): (Int, Int, Int) = {
    val instant = Instant.ofEpochMilli(timestamp)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    val year = dateTime.getYear
    val month = dateTime.getMonthValue
    val day = dateTime.getDayOfMonth
    (year, month, day)
  }
}

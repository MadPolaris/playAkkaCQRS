package net.imadz.application.projection

import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.projection.eventsourced
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.common.application.projection.ScalikeJdbcSession
import net.imadz.domain.entities.CreditBalanceEntity.BalanceChanged
import net.imadz.infrastructure.persistence.CreditBalanceEventAdapter
import net.imadz.infrastructure.proto.credits.{CreditBalanceEventPO => CreditEventPO}

import java.time.{Instant, LocalDateTime, ZoneId}

case class MonthlyIncomeAndExpenseSummaryProjectionHandler(sharding: ClusterSharding,
                                                           repository: MonthlyIncomeAndExpenseSummaryRepository) extends JdbcHandler[eventsourced.EventEnvelope[CreditEventPO.Event], ScalikeJdbcSession] {
  val adapter = new CreditBalanceEventAdapter

  override def process(session: ScalikeJdbcSession, envelope: EventEnvelope[CreditEventPO.Event]): Unit = {
    adapter.fromJournal(envelope.event, "").events.foreach {
      case BalanceChanged(update, timestamp) =>
        val (year, month, day) = getDateFromTimestamp(timestamp)
        val userId = envelope.persistenceId
        if (update.amount > 0)
          repository.updateIncome(userId, update.amount, year, month, day)
        else {
          repository.updateExpense(userId, -update.amount, year, month, day)
        }
      case _ =>
        ()
    }
  }

  private def getDateFromTimestamp(timestamp: Long): (Int, Int, Int) = {
    val instant = Instant.ofEpochMilli(timestamp)
    val dateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    val year = dateTime.getYear
    val month = dateTime.getMonthValue
    val day = dateTime.getDayOfMonth
    (year, month, day)
  }
}

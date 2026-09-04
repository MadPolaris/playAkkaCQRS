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
import java.util.concurrent.ConcurrentHashMap

case class MonthlyIncomeAndExpenseSummaryProjectionHandler(sharding: ClusterSharding,
                                                           repository: MonthlyIncomeAndExpenseSummaryRepository) extends JdbcHandler[eventsourced.EventEnvelope[CreditEventPO.Event], ScalikeJdbcSession] {
  private val logger = LoggerFactory.getLogger(getClass)
  val adapter = new CreditBalanceEventAdapter

  // Track pending incoming credits: transferId -> (userId, amount, year, month, day)
  // Recorded stores the amount; Commited confirms it as actual income; Canceled discards it
  private val pendingIncomingCredits = new ConcurrentHashMap[String, (String, BigDecimal, Int, Int, Int)]()

  // Track which transfers have been deducted (Commit phase), so ReservationReleased
  // can distinguish Commit compensation (must reverse expense) from Prepare compensation (no-op)
  private val deductedTransfers = ConcurrentHashMap.newKeySet[String]()

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
          logger.info(s"Ignoring FundsReserved for $userId (Transfer: $transferId) - expense only recognized at Deduct time")
          ()

        case FundsDeducted(transferId, amount) =>
          logger.info(s"Processing FundsDeducted for $userId: ${amount.amount} (Transfer: $transferId)")
          deductedTransfers.add(transferId.toString)
          repository.updateExpense(userId, amount.amount, year, month, day)

        case ReservationReleased(transferId, amount) =>
          val key = transferId.toString
          if (deductedTransfers.remove(key)) {
            logger.info(s"Reversing expense for $userId: ${amount.amount} (Commit compensation, Transfer: $transferId)")
            repository.updateExpense(userId, -amount.amount, year, month, day)
          } else {
            logger.info(s"Ignoring ReservationReleased for $userId (Transfer: $transferId) - FundsDeducted was not processed (Prepare-phase compensation)")
          }

        case IncomingCreditsRecorded(transferId, amount) =>
          logger.info(s"Caching pending incoming credit for $userId: ${amount.amount} (Transfer: $transferId)")
          pendingIncomingCredits.put(transferId.toString, (userId, amount.amount, year, month, day))

        case IncomingCreditsCommited(transferId) =>
          val key = transferId.toString
          val pending = pendingIncomingCredits.remove(key)
          if (pending != null) {
            val (uid, amt, y, m, d) = pending
            logger.info(s"Processing IncomingCreditsCommited for $uid: $amt (Transfer: $transferId)")
            repository.updateIncome(uid, amt, y, m, d)
          } else {
            logger.warn(s"IncomingCreditsCommited for transferId=$key but no pending amount found (possible restart between Recorded and Commited)")
          }

        case IncomingCreditsCanceled(transferId) =>
          val key = transferId.toString
          val removed = pendingIncomingCredits.remove(key)
          if (removed != null) {
            logger.info(s"Cancelled pending incoming credit for transferId=$key")
          } else {
            logger.warn(s"IncomingCreditsCanceled for transferId=$key but no pending amount found")
          }

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

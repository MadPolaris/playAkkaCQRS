package net.imadz.application.projection.repository

import com.google.inject.ImplementedBy
import net.imadz.infrastructure.repositories.projection.MonthlyIncomeAndExpenseSummaryRepositoryImpl

import scala.concurrent.Future

object MonthlyIncomeAndExpenseSummaryTable {
  case class MonthlyIncomeAndExpenseSummary(userId: String, income: BigDecimal, expense: BigDecimal, year: Int, month: Int)
}

@ImplementedBy(classOf[MonthlyIncomeAndExpenseSummaryRepositoryImpl])
trait MonthlyIncomeAndExpenseSummaryRepository {

  import MonthlyIncomeAndExpenseSummaryTable._

  def filterByPeriodLCRO(userId: String, startFromYear: Int, startFromMonth: Int, untilYear: Int, untilMonth: Int): Future[List[MonthlyIncomeAndExpenseSummary]]

  def updateIncome(userId: String, amount: BigDecimal, year: Int, month: Int, day: Int): Unit

  def updateExpense(userId: String, amount: BigDecimal, year: Int, month: Int, day: Int): Unit

}
package net.imadz.infrastructure.repositories.projection

import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryTable.MonthlyIncomeAndExpenseSummary
import scalikejdbc._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class MonthlyIncomeAndExpenseSummaryRepositoryImpl extends MonthlyIncomeAndExpenseSummaryRepository {

  override def filterByPeriodLCRO(userId: String, startFromYear: Int, startFromMonth: Int, untilYear: Int, untilMonth: Int): Future[List[MonthlyIncomeAndExpenseSummary]] = Future {
    DB readOnly { implicit session =>
      sql"""
        SELECT user_id, income, expense, year, month
        FROM monthly_income_and_expense_summary
        WHERE user_id = ${userId}
          AND ((year > ${startFromYear}) OR (year = ${startFromYear} AND month >= ${startFromMonth}))
          AND ((year < ${untilYear}) OR (year = ${untilYear} AND month <= ${untilMonth}))
        ORDER BY year, month
      """
        .map(rs => MonthlyIncomeAndExpenseSummary(
          userId = rs.string("user_id"),
          income = rs.bigDecimal("income"),
          expense = rs.bigDecimal("expense"),
          year = rs.int("year"),
          month = rs.int("month")
        )).list.apply()
    }
  }

  override def updateIncome(userId: String, amount: BigDecimal, year: Int, month: Int, day: Int): Unit = {
    DB localTx { implicit session =>
      sql"""
        INSERT INTO monthly_income_and_expense_summary (user_id, income, year, month, day)
        VALUES (${userId}, ${amount}, ${year}, ${month}, ${day})
        ON DUPLICATE KEY UPDATE
        income = income + VALUES(income)
      """.update.apply()
    }
  }

  override def updateExpense(userId: String, amount: BigDecimal, year: Int, month: Int, day: Int): Unit = {
    DB localTx { implicit session =>
      sql"""
        INSERT INTO monthly_income_and_expense_summary (user_id, expense, year, month, day)
        VALUES (${userId}, ${amount}, ${year}, ${month}, ${day})
        ON DUPLICATE KEY UPDATE
        expense = expense + VALUES(expense)
      """.update.apply()
    }
  }
}

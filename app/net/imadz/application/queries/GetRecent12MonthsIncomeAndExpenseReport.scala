package net.imadz.application.queries

import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.queries.GetRecent12MonthsIncomeAndExpenseReport.{MonthlyIncomeAndExpense, Recent12MonthsIncomeAndExpenseReport}
import net.imadz.common.CommonTypes.{Id, ReadModel}

import java.time.{LocalDate, YearMonth}
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object GetRecent12MonthsIncomeAndExpenseReport {

  case class MonthlyIncomeAndExpense(year: Int, month: Int, incomeTotal: BigDecimal, expenseTotal: BigDecimal)

  case class Recent12MonthsIncomeAndExpenseReport(monthlySummaries: List[MonthlyIncomeAndExpense]) extends ReadModel
}

class GetRecent12MonthsIncomeAndExpenseReport @Inject()(repository: MonthlyIncomeAndExpenseSummaryRepository) {

  def fetchByUserId(userId: Id): Future[Recent12MonthsIncomeAndExpenseReport] = {
    val now = LocalDate.now()
    val untilYearMonth = YearMonth.from(now)
    val startFromYearMonth = untilYearMonth.minusMonths(11)

    val startFromYear = startFromYearMonth.getYear
    val startFromMonth = startFromYearMonth.getMonthValue
    val untilYear = untilYearMonth.getYear
    val untilMonth = untilYearMonth.getMonthValue

    repository.filterByPeriodLCRO(userId.toString, startFromYear, startFromMonth, untilYear, untilMonth)
      .map(xs => Recent12MonthsIncomeAndExpenseReport(
        xs.map(x => MonthlyIncomeAndExpense(x.year, x.month, x.income, x.expense))
      ))
  }
}

package controllers

import akka.actor.ActorSystem
import akka.actor.typed.javadsl.Adapter
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.queries.{GetBalanceQuery, GetRecent12MonthsIncomeAndExpenseReport}
import net.imadz.application.services.{CreateCreditBalanceService, DepositService, MoneyTransferService, WithdrawService}
import net.imadz.infrastructure.bootstrap.{CreditBalanceBootstrap, MonthlyIncomeAndExpenseBootstrap, TransactionBootstrap}
import play.api.mvc._

import javax.inject._
import scala.concurrent.ExecutionContext

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(val system: ActorSystem,
  val sharding: ClusterSharding,
  val monthlyQuery: GetRecent12MonthsIncomeAndExpenseReport,
  val monthlyRepository: MonthlyIncomeAndExpenseSummaryRepository,
  val getBalanceQuery: GetBalanceQuery,
  val createService: CreateCreditBalanceService,
  val depositService: DepositService,
  val withdrawService: WithdrawService,
  val moneyTransferService: MoneyTransferService,
  val controllerComponents: ControllerComponents
)(implicit executionContext: ExecutionContext) extends BaseController
  with MonthlyIncomeAndExpenseBootstrap
  with CreditBalanceBootstrap
  with TransactionBootstrap {

  initMonthlySummaryProjection(Adapter.toTyped(system), sharding, monthlyRepository)
  initCreditBalanceAggregate(sharding)
  initTransactionAggregate(sharding)

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }
}

package controllers

import akka.actor.typed.Scheduler
import akka.actor.typed.javadsl.Adapter
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorSystem, typed}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.queries.{GetBalanceQuery, GetRecent12MonthsIncomeAndExpenseReport}
import net.imadz.application.services.{CreateCreditBalanceService, DepositService, MoneyTransferService, WithdrawService}
import net.imadz.common.Id
import net.imadz.domain.values.Money
import net.imadz.infrastructure.bootstrap._
import play.api.mvc._

import java.util.Currency
import javax.inject._
import scala.concurrent.ExecutionContext

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(
  val system: ActorSystem,
  val sharding: ClusterSharding,
  val monthlyQuery: GetRecent12MonthsIncomeAndExpenseReport,
  val monthlyRepository: MonthlyIncomeAndExpenseSummaryRepository,
  val creditBalanceRepository: CreditBalanceRepository,
  val coordinatorRepository: TransactionCoordinatorRepository,
  val getBalanceQuery: GetBalanceQuery,
  val createService: CreateCreditBalanceService,
  val depositService: DepositService,
  val withdrawService: WithdrawService,
  val moneyTransferService: MoneyTransferService,
  val controllerComponents: ControllerComponents
)(implicit executionContext: ExecutionContext) extends BaseController
  with MonthlyIncomeAndExpenseBootstrap
  with CreditBalanceBootstrap
  with TransactionBootstrap
  with SagaTransactionCoordinatorBootstrap {
  val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
  implicit val scheduler: Scheduler = typedSystem.scheduler

  initMonthlySummaryProjection(Adapter.toTyped(system), sharding, monthlyRepository)
  initCreditBalanceAggregate(sharding)
  initSagaTransactionCoordinatorAggregate(sharding, creditBalanceRepository)
  initTransactionAggregate(sharding, coordinatorRepository, creditBalanceRepository)

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

  def getBalance(userId: String): Action[AnyContent] = Action.async { implicit request =>
    getBalanceQuery.fetchBalanceByUserId(Id.of(userId)).map { balance =>
      Ok(balance.toString) // Assuming `balance` has a proper `toString` method
    }.recover {
      case ex: Exception => InternalServerError(ex.getMessage)
    }
  }

  def deposit(userId: String, amount: Double): Action[AnyContent] = Action.async { implicit request =>
    depositService.requestDeposit(Id.of(userId), Money(amount, Currency.getInstance("CNY"))).map { confirmation =>
      Ok(confirmation.toString)
    }.recover {
      case ex: Exception => InternalServerError(ex.getMessage)
    }
  }

  def withdraw(userId: String, amount: Double): Action[AnyContent] = Action.async { implicit request =>
    withdrawService.requestWithdraw(Id.of(userId), Money(amount, Currency.getInstance("CNY"))).map { confirmation =>
      Ok(confirmation.toString)
    }.recover {
      case ex: Exception => InternalServerError(ex.getMessage)
    }
  }

  def transfer(fromUserId: String, toUserId: String, amount: Double): Action[AnyContent] = Action.async { implicit request =>
    moneyTransferService.transfer(Id.of(fromUserId), Id.of(toUserId), Money(amount, Currency.getInstance("CNY"))).map { confirmation =>
      Ok(confirmation.toString)
    }.recover {
      case ex: Exception => InternalServerError(ex.getMessage)
    }
  }
}


package controllers

import akka.actor.typed.Scheduler
import akka.actor.typed.javadsl.Adapter
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorSystem, typed}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import net.imadz.application.aggregates.CreditBalanceProtocol.CreditBalanceConfirmation
import net.imadz.application.aggregates.repository.CreditBalanceRepository
import net.imadz.application.projection.repository.MonthlyIncomeAndExpenseSummaryRepository
import net.imadz.application.queries.{GetBalanceQuery, GetRecent12MonthsIncomeAndExpenseReport}
import net.imadz.application.services.transactor.MoneyTransferTransactionRepository
import net.imadz.application.services.{CreateCreditBalanceService, DepositService, MoneyTransferService, WithdrawService}
import net.imadz.common.CommonTypes.{Id, iMadzError}
import net.imadz.common.Id
import net.imadz.common.application.projection.ScalikeJdbcSetup
import net.imadz.domain.values.Money
import net.imadz.infra.saga.repository.TransactionCoordinatorRepository
import net.imadz.infrastructure.bootstrap._
import play.api.libs.json._
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
                                val transactionRepository: MoneyTransferTransactionRepository,
                                val getBalanceQuery: GetBalanceQuery,
                                val depositService: DepositService,
                                val moneyTransferService: MoneyTransferService,
                                val withdrawService: WithdrawService,
                                val controllerComponents: ControllerComponents
//                                , val bootstrap: net.imadz.infrastructure.bootstrap.ApplicationBootstrap
                              )(implicit executionContext: ExecutionContext) extends BaseController {
  val typedSystem: typed.ActorSystem[Nothing] = system.toTyped
  ScalikeJdbcSetup.init(Adapter.toTyped(system))

  implicit val scheduler: Scheduler = typedSystem.scheduler

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

  implicit val iMadzErrorFormat: OFormat[iMadzError] = Json.format[iMadzError]
  implicit val moneyFormat: Format[Money] = new Format[Money] {
    def writes(o: Money): JsValue = Json.obj(
      "amount" -> o.amount,
      "currency" -> o.currency.getCurrencyCode
    )

    def reads(json: JsValue): JsResult[Money] = for {
      amount <- (json \ "amount").validate[BigDecimal]
      currencyCode <- (json \ "currency").validate[String]
    } yield Money(amount, Currency.getInstance(currencyCode))
  }
  implicit val idFormat: Format[Id] = new Format[Id] {
    override def writes(o: Id): JsValue = JsString(o.toString)

    override def reads(json: JsValue): JsResult[Id] = JsSuccess(Id.of(json.toString()))
  }


  implicit val eitherErrorIdFormat: Format[Either[iMadzError, Id]] = new Format[Either[iMadzError, Id]] {
    def reads(json: JsValue): JsResult[Either[iMadzError, Id]] = {
      (json \ "error").toOption match {
        case Some(errorJson) => iMadzErrorFormat.reads(errorJson).map(Left(_))
        case None => (json \ "id").toOption match {
          case Some(idJson) => idFormat.reads(idJson).map(Right(_))
          case None => JsError("Invalid format: neither error nor id found")
        }
      }
    }

    def writes(either: Either[iMadzError, Id]): JsValue = either match {
      case Left(error) => Json.obj("error" -> iMadzErrorFormat.writes(error))
      case Right(id) => Json.obj("id" -> idFormat.writes(id))
    }
  }

  def getBalance(userId: String): Action[AnyContent] = Action.async { implicit request =>
    getBalanceQuery.fetchBalanceByUserId(Id.of(userId)).map { balance =>
      Ok(Json.toJson(balance)) // Assuming `balance` has a proper `toString` method
    }.recover {
      case ex: Exception => InternalServerError(ex.getMessage)
    }
  }

  implicit val creditBalanceConfirmationFormat: OFormat[CreditBalanceConfirmation] = Json.format[CreditBalanceConfirmation]

  def deposit(userId: String, amount: Double): Action[AnyContent] = Action.async { implicit request =>
    depositService.requestDeposit(Id.of(userId), Money(amount, Currency.getInstance("CNY"))).map { confirmation =>
      Ok(Json.toJson(confirmation))
    }.recover {
      case ex: Exception => InternalServerError(ex.getMessage)
    }
  }

  def withdraw(userId: String, amount: Double): Action[AnyContent] = Action.async { implicit request =>
    withdrawService.requestWithdraw(Id.of(userId), Money(amount, Currency.getInstance("CNY"))).map { confirmation =>
      Ok(Json.toJson(confirmation)) // Assuming `balance` has a proper `toString` method
    }.recover {
      case ex: Exception => InternalServerError(ex.getMessage)
    }
  }

  implicit val idWriter: Writes[Id] = new Writes[Id] {
    override def writes(o: Id): JsValue = JsString(o.toString)
  }

  def transfer(fromUserId: String, toUserId: String, amount: Double): Action[AnyContent] = Action.async { implicit request =>
    moneyTransferService.transfer(Id.of(fromUserId), Id.of(toUserId), Money(amount, Currency.getInstance("CNY"))).map { confirmation =>
      Ok(Json.toJson(confirmation))
    }.recover {
      case ex: Exception => InternalServerError(ex.getMessage)
    }
  }
}


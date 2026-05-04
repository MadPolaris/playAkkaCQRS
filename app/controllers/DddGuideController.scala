package controllers

import play.api.mvc._
import play.api.i18n.I18nSupport
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.json._
import scalikejdbc._

import net.imadz.application.queries.GetBalanceQuery
import net.imadz.common.Id

@Singleton
class DddGuideController @Inject()(val controllerComponents: ControllerComponents, 
                                   val getBalanceQuery: GetBalanceQuery)(implicit ec: ExecutionContext) extends BaseController with I18nSupport {

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.dddGuide())
  }

  def projectionShowcase() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.projectionShowcase())
  }

  def getBalances(ids: String) = Action.async {
    val idList = ids.split(",").map(Id.of).toList
    Future.sequence(idList.map(id => getBalanceQuery.fetchBalanceByUserId(id).map(b => id.toString -> b)))
      .map(results => {
        val balancesMap: Map[String, JsObject] = results.map { case (id, b) =>
          val amount: BigDecimal = b.headOption.map(_.amount).getOrElse(BigDecimal(0))
          id -> Json.obj("balance" -> amount)
        }.toMap
        Ok(Json.toJson(balancesMap))
      })
  }
  def getProjectionStatus() = Action.async {
    Future {
      DB.readOnly { implicit session =>
        // ... (existing logic)
        val offsets = sql"SELECT projection_name, current_offset, last_updated FROM akka_projection_offset_store"
          .map(rs => Json.obj(
            "name" -> rs.string("projection_name"),
            "offset" -> rs.string("current_offset"),
            "lastUpdated" -> rs.long("last_updated")
          )).list().apply()

        val summaries = sql"SELECT user_id, year, month, income, expense FROM monthly_income_and_expense_summary WHERE year > 1970 ORDER BY year DESC, month DESC LIMIT 10"
          .map(rs => Json.obj(
            "userId" -> rs.string("user_id"),
            "year" -> rs.int("year"),
            "month" -> rs.int("month"),
            "income" -> rs.double("income"),
            "expense" -> rs.double("expense")
          )).list().apply()

        Ok(Json.obj(
          "offsets" -> offsets,
          "summaries" -> summaries
        ))
      }
    }
  }
}

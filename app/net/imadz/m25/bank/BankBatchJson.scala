package net.imadz.m25.bank

import play.api.libs.json.{JsValue, Json}

/** JSON frame builder for the bank-batch WebSocket page. */
object BankBatchJson {
  def frame(seq: Long, `type`: String, data: Map[String, Any]): String = {
    val dataJson: Map[String, JsValue] = data.map {
      case (k, v: String) => k -> Json.toJson(v)
      case (k, v: Int) => k -> Json.toJson(v)
      case (k, v: Long) => k -> Json.toJson(v)
      case (k, v: Double) => k -> Json.toJson(v)
      case (k, v: Boolean) => k -> Json.toJson(v)
      case (k, v: Seq[String]) => k -> Json.toJson(v)
      case (k, v) => k -> Json.toJson(v.toString)
    }
    Json.stringify(Json.obj("seq" -> seq, "type" -> `type`, "data" -> Json.toJson(dataJson)))
  }

  /** Explicitly typed — a Map[String, Any] match would fall into erasure traps
    * (List[String] matching Seq[Map[...]]). */
  def stateJson(state: Map[String, Any]): String = {
    import play.api.libs.json._
    val doneStages = state("doneStages").asInstanceOf[List[String]]
    val orders = state("orders").asInstanceOf[Seq[Map[String, Any]]]
    val ledger = state("ledger").asInstanceOf[java.util.List[String]]
    val balances = state("balances").asInstanceOf[Map[String, String]]
    Json.stringify(Json.obj(
      "batchId" -> state("batchId").toString,
      "currentStage" -> state("currentStage").toString,
      "doneStages" -> doneStages,
      "orders" -> orders.map(o => Json.obj(
        "orderNo" -> o("orderNo").toString, "customer" -> o("customer").toString,
        "amount" -> o("amount").toString, "status" -> o("status").toString)),
      "ledger" -> {
        import scala.jdk.CollectionConverters._
        JsArray(ledger.asScala.map(JsString).toSeq)
      },
      "balances" -> balances))
  }
}

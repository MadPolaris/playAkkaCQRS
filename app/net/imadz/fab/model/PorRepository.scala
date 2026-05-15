package net.imadz.fab.model

/**
 * In-memory repository for ProductRouting lookup.
 *
 * In production, this would query a POR (Process of Record) database.
 * For the M3.5 demo, routes are pre-registered and looked up by product ID.
 */
object PorRepository {

  private var routings: Map[String, Por] = Map(
    Por.logic28nmPor.productId -> Por.logic28nmPor
  )

  def findByProductId(productId: String): Option[Por] =
    routings.get(productId)

  def register(routing: Por): Unit = {
    routings += routing.productId -> routing
  }

  def listProducts: Seq[Por] =
    routings.values.toSeq.sortBy(_.productId)
}

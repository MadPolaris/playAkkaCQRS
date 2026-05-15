package net.imadz.fab.simulation

import scala.concurrent.duration._

/**
 * Simulation configuration — wires together all equipment configs.
 * speedMultiplier: 1.0 = real-time, 5.0 = 5x, 10.0 = 10x
 */
case class FabSimulationConfig(
  litho: EquipmentConfig,
  cdSem: EquipmentConfig,
  amhs: AmhsConfig,
  stocker: StockerConfig,
  speedMultiplier: Double = 1.0
) {
  def withSpeed(m: Double): FabSimulationConfig = copy(speedMultiplier = m)
}

case class EquipmentConfig(
  equipmentId: String,
  areaId: String,
  processingTime: FiniteDuration = 30.seconds
)

case class AmhsConfig(
  routes: Map[(String, String), FiniteDuration] = Map.empty,
  maxConcurrentTransports: Int = 3
)

case class StockerConfig(
  equipmentId: String,
  portCount: Int = 4,
  loadTime: FiniteDuration = 5.seconds
)

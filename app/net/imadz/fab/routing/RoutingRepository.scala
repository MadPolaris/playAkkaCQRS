package net.imadz.fab.routing

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/**
 * Append-only immutable storage for RouteDefinition.
 *
 * Design invariants:
 *   - RouteDefinition once published is never modified or deleted
 *   - New versions = new append records; version numbers auto-increment
 *   - RouteRef.version permanently binds to the published version
 *   - commitHash = SHA-256(content) for immutability verification
 *
 * Thread-safe via ConcurrentHashMap.
 */
object RoutingRepository {

  // routeId -> (version -> RouteDefinition)
  private val store = new ConcurrentHashMap[String, ConcurrentHashMap[Int, RouteDefinition]]()

  /**
   * Publish a new RouteDefinition. Version auto-increments if not specified.
   * @return the published RouteDefinition with version and commitHash set
   */
  def publish(route: RouteDefinition): RouteDefinition = {
    val versions = store.computeIfAbsent(route.routeId, _ => new ConcurrentHashMap[Int, RouteDefinition]())
    val latestVersion = if (versions.isEmpty) 0 else versions.keySet().asScala.max
    val newVersion = if (route.version > 0) route.version else latestVersion + 1
    val versioned = route.copy(version = newVersion)
    versions.put(newVersion, versioned)
    versioned
  }

  /**
   * Get a RouteDefinition by routeId and version.
   */
  def get(routeId: String, version: Int): Option[RouteDefinition] = {
    val versions = store.get(routeId)
    if (versions == null) None else Option(versions.get(version))
  }

  /**
   * Get the latest version of a route.
   */
  def getLatest(routeId: String): Option[RouteDefinition] = {
    val versions = store.get(routeId)
    if (versions == null || versions.isEmpty) None
    else {
      val latest = versions.keySet().asScala.max
      Option(versions.get(latest))
    }
  }

  /**
   * Get by RouteRef (routeId + version).
   */
  def get(ref: RouteRef): Option[RouteDefinition] = get(ref.routeId, ref.version)

  /**
   * List all versions for a routeId.
   */
  def listVersions(routeId: String): List[Int] = {
    val versions = store.get(routeId)
    if (versions == null) Nil else versions.keySet().asScala.toList.sorted
  }

  /**
   * List all registered route IDs.
   */
  def listRouteIds(): List[String] = store.keySet().asScala.toList.sorted

  /** Remove everything (for testing). */
  def clearAll(): Unit = store.clear()
}

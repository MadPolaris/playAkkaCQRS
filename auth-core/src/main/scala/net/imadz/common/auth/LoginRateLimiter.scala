package net.imadz.common.auth

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import javax.inject.Singleton

/**
 * 登录限流（Authentication_Design.md §一）：username+IP 滑动窗口，
 * 15 分钟内 5 次失败 → 429 RATE_LIMITED。成功登录清除计数。
 */
@Singleton
class LoginRateLimiter {
  private val MaxAttempts = 5
  private val WindowMillis = 15 * 60 * 1000L
  private val cache: Cache[String, List[Long]] =
    Caffeine.newBuilder().expireAfterAccess(java.time.Duration.ofMinutes(20)).build()

  def isBlocked(username: String, ip: String): Boolean = {
    val now = System.currentTimeMillis()
    recentFailures(username, ip, now) >= MaxAttempts
  }

  def recordFailure(username: String, ip: String): Unit = {
    val key = s"$ip:$username"
    val now = System.currentTimeMillis()
    val existing = Option(cache.getIfPresent(key)).getOrElse(Nil)
    cache.put(key, (now :: existing).filter(_ > now - WindowMillis))
  }

  def recordSuccess(username: String, ip: String): Unit =
    cache.invalidate(s"$ip:$username")

  private def recentFailures(username: String, ip: String, now: Long): Int =
    Option(cache.getIfPresent(s"$ip:$username"))
      .map(_.count(_ > now - WindowMillis))
      .getOrElse(0)
}

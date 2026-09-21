package net.imadz.common.auth

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-SHA256 密码哈希（Authentication_Design.md P7：310k iters，OWASP 2023）。
 * 结构参考 timed-box SecurePasswordHashing（BACKLOG S2-9 复用项）。
 * 格式：iterations:saltB64:hashB64
 */
object PasswordHashing {
  private val RandomSource = new SecureRandom()
  private val Iterations = 310000
  private val SaltBytes = 16
  private val HashBytes = 32

  def hash(password: String): String = {
    val salt = new Array[Byte](SaltBytes)
    RandomSource.nextBytes(salt)
    val hash = pbkdf2(password, salt, Iterations)
    s"$Iterations:${toB64(salt)}:${toB64(hash)}"
  }

  /** 时序安全的校验 */
  def verify(password: String, stored: String): Boolean =
    parse(stored) match {
      case Some((iters, salt, expected)) =>
        val candidate = pbkdf2(password, salt, iters)
        constantTimeEq(candidate, expected)
      case None => false
    }

  private def parse(stored: String): Option[(Int, Array[Byte], Array[Byte])] =
    stored.split(":") match {
      case Array(iters, saltB64, hashB64) =>
        try Some((iters.toInt, fromB64(saltB64), fromB64(hashB64)))
        catch { case _: Throwable => None }
      case _ => None
    }

  private def pbkdf2(password: String, salt: Array[Byte], iterations: Int): Array[Byte] = {
    val spec = new PBEKeySpec(password.toCharArray, salt, iterations, HashBytes * 8)
    SecretKeyFactory.getInstance("PBKDF2WithHMACSHA256").generateSecret(spec).getEncoded
  }

  private def constantTimeEq(a: Array[Byte], b: Array[Byte]): Boolean =
    if (a.length != b.length) false
    else {
      var diff = 0
      for (i <- a.indices) diff |= a(i) ^ b(i)
      diff == 0
    }

  private def toB64(bytes: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(bytes)
  private def fromB64(s: String): Array[Byte] = java.util.Base64.getDecoder.decode(s)
}

package org.chronobench

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object IdGen {
    // 固定风格的稳定身份；实体逻辑 id 由导入层显式指定，这里只给派生对象/项目。
    private val rng = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(20260919L) }
    private val ts = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    fun derivation(projectId: String): String =
        "D-${projectId}-${ts.format(Instant.now())}-${counter.incrementAndGet()}"
    fun event(projectId: String, seq: Long): String = "E-${projectId}-$seq"
    fun token(): String = "%010d".format(rng.nextLong().let { if (it < 0) -it else it })
}

object Fingerprints {
    private fun sha256(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        return b.joinToString("") { "%02x".format(it) }
    }
    fun of(vararg parts: String): String = "sha256:" + sha256(parts.joinToString("|"))
    fun short(s: String): String = s.removePrefix("sha256:").take(12)
}

/**
 * 年代坐标。统一内部坐标 = calBP（距 1950 的整年，正数=过去）。
 * 天文年: astro = 1950 - calBP，含天文 0 年（=1 BCE）。
 * BCE/CE: astro<=0 -> (1-astro) BCE；astro>=1 -> astro CE。
 */
object Eras {
    fun astronomicalYear(calBP: Int): Int = 1950 - calBP
    fun eraLabel(calBP: Int): String {
        val a = astronomicalYear(calBP)
        return when {
            a <= 0 -> "${1 - a} BCE"
            else -> "$a CE"
        }
    }
    fun crossesBceCe(startCalBP: Int, endCalBP: Int): Boolean {
        val a0 = astronomicalYear(startCalBP)
        val a1 = astronomicalYear(endCalBP)
        val lo = minOf(a0, a1); val hi = maxOf(a0, a1)
        return lo <= 0 && hi >= 1
    }
}

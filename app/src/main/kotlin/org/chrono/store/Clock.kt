package org.chrono.store

import java.time.Instant

/** 可注入时钟：演示用系统时钟，测试用固定/虚拟时间。 */
fun interface Clock { fun now(): Instant }
val SystemClock = Clock { Instant.now() }
class VirtualClock(private var instant: Instant = Instant.parse("2024-01-01T00:00:00Z")) : Clock {
    override fun now(): Instant = instant
    fun advanceSeconds(s: Long) { instant = instant.plusSeconds(s) }
    fun setTo(i: Instant) { instant = i }
}

/**
 * 稳定身份：eventId = 内容 FNV 指纹 + seq（导入再导入保持一致，因为内容与顺序一致）。
 * 对同内容事件（如重复导入）仍由 seq 区分顺序，但内容指纹保证可重放校验。
 */
object StableIds {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    fun typeCode(prefix: String, contentKey: String): String {
        val h = org.chrono.domain.FNV1a()
        h.update(prefix); h.update(contentKey)
        return "$prefix-${h.hex().take(10)}"
    }
}

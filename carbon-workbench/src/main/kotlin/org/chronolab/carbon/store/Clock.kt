package org.chronolab.carbon.store

import java.time.Instant

/**
 * 虚拟时间：默认冻结在固定时刻并由测试显式推进，保证固定 seed/临时目录/虚拟时间的可重现性。
 */
class VirtualClock(
    private var current: Instant = Instant.parse("2026-09-01T00:00:00Z")
) {
    fun now(): Instant = current
    fun nowIso(): String = current.toString()
    fun advanceSeconds(s: Long) { current = current.plusSeconds(s) }
    fun set(instant: Instant) { current = instant }
}

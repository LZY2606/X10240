package gsb.rcarb.project

import java.time.Instant

interface Clock {
    fun now(): Instant
}

/**
 * 虚拟时间：固定锚点 2026-01-01T00:00:00Z，每提交一个事件推进 1 分钟。
 * 重放时时间戳由事件本身携带，因此回到任意旧事件都恢复当时视图。
 */
class VirtualClock(
    private val anchor: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private val stepSeconds: Long = 60,
    private var ticks: Long = 0
) : Clock {
    override fun now(): Instant = anchor.plusSeconds(ticks * stepSeconds)
    fun tick(): Instant {
        ticks += 1
        return now()
    }
    fun advanceTo(count: Long) {
        if (count > ticks) ticks = count
    }
}

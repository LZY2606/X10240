package org.gsb.carbon

/** 可注入时钟：测试使用虚拟时间，生产环境用系统时钟。 */
interface GsbClock {
    fun nowMillis(): Long
}

class SystemClock : GsbClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** 固定 seed 的虚拟时钟：从固定起点按步进推进，保证自动化测试可重现。 */
class VirtualClock(
    startMillis: Long = 1_700_000_000_000L,
    private val stepMillis: Long = 60_000L,
) : GsbClock {
    private var current: Long = startMillis
    override fun nowMillis(): Long {
        val v = current
        current += stepMillis
        return v
    }
}

/** 固定 seed 的随机源（当前计算确定性，不依赖随机；保留给未来扰动诊断）。 */
class SeededRandom(seed: Long = 20260924L) {
    private var state: Long = seed
    fun nextLong(): Long {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return state
    }
}

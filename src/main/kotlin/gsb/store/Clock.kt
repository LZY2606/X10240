package gsb.store

import java.time.Instant

/** 可注入时钟：测试用虚拟时间，生产用 UTC。 */
fun interface Clock { fun now(): Instant }
val SystemClock = Clock { Instant.now() }

/** 固定 seed 的随机源（测试确定性）。 */
class SeededRng(seed: Long) {
    private var s = seed
    fun nextLong(): Long {
        // xorshift64*
        var x = s
        x = x xor (x ushr 12); x = x xor (x shl 25); x = x xor (x ushr 27)
        s = x
        return x * 2685821657736338717L
    }
    fun nextPositive(): Long = nextLong() and 0x7fffffffffffffffL
}

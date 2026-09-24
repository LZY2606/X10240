package chrono

import kotlin.math.abs

/** 可注入的虚拟时钟，测试固定时间；生产使用系统毫秒。 */
fun interface Clock { fun nowMillis(): Long }

object SystemClock : Clock { override fun nowMillis(): Long = System.currentTimeMillis() }

/**
 * 稳定身份生成：固定 seed 下完全可重现。
 * 同一 scope+key 总是得到同一 id（用于导入重放时身份稳定）；
 * 新事件使用单调序列 + seed 派生。
 */
class IdGenerator(private val seed: Long) {
    private var seq: Long = 0
    fun next(prefix: String): String {
        seq += 1
        val h = fnv1a("$seed#$prefix#$seq")
        return "$prefix-${h.toString(16).take(10).padStart(10, '0')}"
    }
    companion object {
        fun stable(prefix: String, seed: Long, key: String): String {
            val h = fnv1a("$seed|$prefix|$key")
            return "$prefix-${h.toString(16).take(10).padStart(10, '0')}"
        }
        fun fnv1a(s: String): Long {
            var hash = -0x7ee3623bff7abL // FNV offset basis (64bit)
            for (b in s.encodeToByteArray()) {
                hash = hash xor (b.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            return abs(hash)
        }
    }
}

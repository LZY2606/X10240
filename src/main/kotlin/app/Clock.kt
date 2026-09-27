package app

/** 虚拟时钟：测试与演示使用固定起点，绝不读取真实时间，保证可重现。 */
class VirtualClock(private var now: Long) {
    fun tick(): Long {
        now += 1
        return now
    }
    fun current(): Long = now
}

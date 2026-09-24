package gsb.model

import kotlinx.serialization.Serializable

/**
 * 领域量。所有数值都携带单位或坐标基准；raw 保留导入时的原始表示，
 * value 是规范化后的机器可读数值。无法转换时 value=null 并附诊断。
 */
@Serializable
data class Quantity(
    /** 导入时的原始表示，逐字保留。例如 "2150 ± 40"、"310 BP"。 */
    val raw: String,
    /** 规范化数值；无法转换时为 null。 */
    val value: Double? = null,
    /** 量纲单位。 */
    val unit: String,
    /** 坐标基准，例如 "BP1950"、"cal BP"、"BCE/CE"。 */
    val basis: String,
    /** 转换诊断；为空表示正常。 */
    val diagnostics: List<String> = emptyList()
) {
    val isUsable: Boolean get() = value != null && diagnostics.none { it.startsWith("ERROR") }
}

/** 年代系统工具。统一使用“天文纪年整数年”作为内部坐标：1 BCE=0，2 BCE=-1，1 CE=1。 */
object Era {
    /** 从带 BCE/CE 的字符串解析为天文纪年。接受 "100 BCE"、"55 CE"、"0"。 */
    fun parseEra(label: String): Int? {
        val t = label.trim().uppercase().replace("AD", "CE").replace("BC", "BCE")
        val bce = t.contains("BCE")
        val num = Regex("[-+]?\\d+").find(t)?.value?.toIntOrNull() ?: return null
        return if (bce) 1 - num else num
    }

    /** 天文纪年 -> 可读标签。 */
    fun label(astronomicalYear: Int): String = when {
        astronomicalYear <= 0 -> "${1 - astronomicalYear} BCE"
        else -> "$astronomicalYear CE"
    }

    /** cal BP（BP1950）-> 天文纪年。cal BP 1950 => 0（=1 BCE），cal BP 1951 => -1（=2 BCE）。 */
    fun calBpToAstronomical(calBp: Double): Int = (1950 - calBp).toInt()

    /** 天文纪年 -> cal BP 中点。 */
    fun astronomicalToCalBp(year: Int): Double = (1950 - year).toDouble()
}

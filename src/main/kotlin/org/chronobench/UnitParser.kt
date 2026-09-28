package org.chronobench

/**
 * 导入层单位解析：保留原始表示，仅对可识别单位做规范化。
 * 接受 BP / 14C BP / radiocarbon BP（^14C 年）；cal BP 不允许出现在测量年龄上（由调用方按字段语义处理）。
 * 无法转换 -> value=null + 诊断，绝不丢弃原值。
 */
object UnitParser {
    private val num = Regex("""\s*([-+]?\d+(?:\.\d+)?)\s*(.*)""", RegexOption.DOT_MATCHES_ALL)

    fun parseRadioCarbonAge(raw: String, field: String): Quantity {
        val m = num.matchEntire(raw.trim())
            ?: return Quantity(null, "BP-14C-years", raw, rawUnitOf(raw),
                listOf("$field: 无法解析数值 '$raw'"))
        val v = m.groupValues[1].toDoubleOrNull()
        val unitText = m.groupValues[2].trim().lowercase()
        val normalized = when {
            unitText in setOf("", "bp", "14c bp", "bp 14c", "bp (14c)", "radiocarbon bp", "bp 14c years", "^14c bp") ->
                "BP-14C-years"
            unitText in setOf("cal bp", "calbp", "cal. bp") -> "calBP-years"
            else -> "unknown"
        }
        val diags = mutableListOf<String>()
        if (v == null) diags += "$field: 数值不可解析 '$raw'"
        if (normalized == "unknown") diags += "$field: 未知单位 '${m.groupValues[2].trim()}'，保留原始表示"
        if (v != null && v < 0) diags += "$field: 放射性碳年龄为负值，保留但标记异常"
        return Quantity(v?.takeIf { diags.isEmpty() || normalized != "unknown" && it >= 0 },
            normalized.let { if (it == "unknown") "unknown" else it }, raw, m.groupValues[2].trim(), diags)
    }

    fun parsePositiveSigma(raw: String, field: String): Quantity {
        val q = parseRadioCarbonAge(raw, field)
        val diags = q.diagnostics.toMutableList()
        if (q.value != null && q.value <= 0) diags += "$field: 标准差必须为正"
        return q.copy(value = q.value?.takeIf { it > 0 && q.convertible }, diagnostics = diags)
    }

    private fun rawUnitOf(raw: String): String =
        num.matchEntire(raw.trim())?.groupValues?.get(2)?.trim() ?: raw
}

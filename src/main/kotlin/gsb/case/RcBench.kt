package gsb.case

import gsb.model.Quantity
import gsb.model.SourceRef

/**
 * Small, copy-pasteable domain import format (.rcbench).
 * Sections:
 *   CURVE  <idFamily> <version> <anchorBP> <step> <c14,c14,...> | <sig,sig,...>
 *   MEAS   <code> "<context>" <ageBP> <sigmaBP> <deltaR|-> <curveVersion> <era>
 *   OFFSET <code> <name> <mean>,<sigma>,<weight>[ ; <mean>,<sigma>,<weight> ...]
 *   ORDER  <youngerCode> < < <olderCode> : rationale
 *   EXCLUDE <code> : reason
 * Quantities carry units: trailing BP / yr. Unconvertible values are preserved
 * verbatim in Quantity.raw with diagnostics instead of being dropped.
 */
object RcBench {
    data class Parsed(
        val curves: List<RawCurve>,
        val measurements: List<RawMeas>,
        val offsets: List<RawOffset>,
        val orders: List<RawOrder>,
        val excludes: List<RawExclude>
    )
    data class RawCurve(val family: String, val version: String, val anchor: Double,
                        val step: Double, val c14: List<Double>, val sig: List<Double>, val ref: SourceRef)
    data class RawMeas(val code: String, val context: String, val age: Quantity, val sigma: Quantity,
                       val deltaR: Quantity?, val curveVersion: String, val era: String, val ref: SourceRef)
    data class RawOffset(val code: String, val name: String,
                         val scenarios: List<Triple<Double, Double, Double>>, val ref: SourceRef)
    data class RawOrder(val younger: String, val older: String, val why: String, val ref: SourceRef)
    data class RawExclude(val code: String, val reason: String, val ref: SourceRef)

    class FormatException(message: String) : RuntimeException(message)

    fun parse(content: String, sourceId: String, fileName: String, importedAt: String): Parsed {
        val curves = mutableListOf<RawCurve>(); val meas = mutableListOf<RawMeas>()
        val offs = mutableListOf<RawOffset>(); val ords = mutableListOf<RawOrder>()
        val exs = mutableListOf<RawExclude>()
        content.lines().forEachIndexed { idx, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val loc = "$fileName#L${idx + 1}"
            fun ref(label: String, frag: String) = SourceRef(sourceId, label, loc, frag, importedAt)
            val parts = line.split(Regex("\\s+"))
            try {
                when (parts[0]) {
                    "CURVE" -> {
                        val body = line.removePrefix("CURVE").trim()
                        val head = body.substringBefore("|").trim().split(Regex("\\s+"))
                        val sigs = body.substringAfter("|").trim().split(",").map { it.trim().toDouble() }
                        require(head.size >= 5) { "CURVE needs family version anchor step c14list" }
                        val c14 = head[4].split(",").map { it.trim().toDouble() }
                        require(c14.size == sigs.size) { "c14/sigma length mismatch ${c14.size}!=${sigs.size}" }
                        curves.add(RawCurve(head[0], head[1], head[2].toDouble(), head[3].toDouble(),
                            c14, sigs, ref("curve ${head[0]} ${head[1]}", line)))
                    }
                    "MEAS" -> {
                        // MEAS code "context with spaces" age sigma delta curveVersion era
                        val m = Regex(
                            """MEAS\s+(\S+)\s+"([^"]*)"\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)"""
                        ).find(line) ?: throw FormatException(
                            "MEAS needs code \"context\" age sigma delta curveVersion era at " + loc)
                        val g = m.groupValues
                        val age = quantity(g[3], "14C_BP")
                        val sig = quantity(g[4], "14C_BP")
                        val delta = if (g[5] == "-") null else quantity(g[5], "yr")
                        meas.add(RawMeas(g[1], g[2], age, sig, delta, g[6], g[7],
                            ref("meas ${g[1]}", line)))
                    }
                    "OFFSET" -> {
                        val body = line.removePrefix("OFFSET").trim()
                        val headTok = body.split(Regex("\\s+"))
                        val code = headTok[0]; val name = headTok[1]
                        val sc = body.substringAfter(name).trim().split(";").map {
                            val t = it.trim().split(",").map { x -> x.trim().toDouble() }
                            require(t.size == 3) { "offset scenario needs mean,sigma,weight" }
                            Triple(t[0], t[1], t[2])
                        }
                        offs.add(RawOffset(code, name, sc, ref("offset $code $name", line)))
                    }
                    "ORDER" -> {
                        val m = Regex("ORDER\\s+(\\S+)\\s+<<\\s+(\\S+)\\s*:\\s*(.*)").find(line)
                            ?: throw FormatException("ORDER must be 'ORDER younger << older : rationale' at $loc")
                        ords.add(RawOrder(m.groupValues[1], m.groupValues[2], m.groupValues[3],
                            ref("order ${m.groupValues[1]} << ${m.groupValues[2]}", line)))
                    }
                    "EXCLUDE" -> {
                        val m = Regex("EXCLUDE\\s+(\\S+)\\s*:\\s*(.*)").find(line)
                            ?: throw FormatException("EXCLUDE must be 'EXCLUDE code : reason' at $loc")
                        exs.add(RawExclude(m.groupValues[1], m.groupValues[2],
                            ref("exclude ${m.groupValues[1]}", line)))
                    }
                    else -> throw FormatException("unknown directive '${parts[0]}' at $loc")
                }
            } catch (ex: Exception) {
                throw FormatException("${ex.message} (at $loc)")
            }
        }
        return Parsed(curves, meas, offs, ords, exs)
    }

    private fun quantity(token: String, expectedUnit: String): Quantity {
        val m = Regex("^(-?[0-9.]+)([A-Za-z_]*)$").matchEntire(token)
            ?: return Quantity(token, null, expectedUnit,
                listOf("unparseable quantity '$token' for unit $expectedUnit; kept verbatim"))
        val v = m.groupValues[1].toDoubleOrNull()
        val unit = m.groupValues[2].ifEmpty { expectedUnit }
        val diag = mutableListOf<String>()
        if (v == null) diag += "non-numeric value '${m.groupValues[1]}'"
        if (unit != expectedUnit) diag += "unit '$unit' != expected '$expectedUnit'; value retained raw"
        return Quantity(token, v, if (unit == expectedUnit) unit else unit, diag)
    }
}

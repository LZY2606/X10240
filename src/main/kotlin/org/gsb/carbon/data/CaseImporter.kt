package org.gsb.carbon.data

import org.gsb.carbon.model.CalibrationCurve
import org.gsb.carbon.model.CurvePoint
import org.gsb.carbon.model.EraSystem
import org.gsb.carbon.model.Measure
import org.gsb.carbon.model.OffsetComponent
import java.io.File

/**
 * 导入层：接受可复制的小型样例，也明确报告格式错误。
 * - 原始表示保留（Measure.rawText），派生层才做规范化；
 * - 无法转换的值留在原位置并附诊断，绝不为画图而丢；
 * - 曲线点文件为领域 CSV：calBP,14C_age_BP,sigma（首行表头）。
 */
object CaseImporter {
    private val knownUnits = mapOf(
        "14C yr BP" to 1.0,
        "14C yr" to 1.0,
        "BP" to 1.0,
        "radiocarbon years BP" to 1.0,
        "cal yr BP" to null,
    )

    fun loadPack(dir: File): Pair<CasePack, List<ImportDiagnostic>> {
        val diags = mutableListOf<ImportDiagnostic>()
        val manifest = File(dir, "manifest.json")
        if (!manifest.isFile) {
            return CasePack("missing", "missing", "missing") to listOf(
                ImportDiagnostic(ImportDiagnostic.Severity.REJECTED, "manifest.json", null,
                    "案例包缺少 manifest.json", if (manifest.exists()) manifest.readText() else ""),
            )
        }
        val pack = try {
            JsonCodec.json.decodeFromString(CasePack.serializer(), manifest.readText())
        } catch (t: Throwable) {
            diags += ImportDiagnostic(ImportDiagnostic.Severity.REJECTED, "manifest.json", null,
                "manifest.json 解析失败：${t.message}", manifest.readText().take(400))
            return CasePack("broken", "broken", "broken") to diags
        }
        validateManifest(pack, diags)
        return pack to diags
    }

    private fun validateManifest(pack: CasePack, diags: MutableList<ImportDiagnostic>) {
        if (pack.measurements.isEmpty()) diags += ImportDiagnostic(
            ImportDiagnostic.Severity.REJECTED, "manifest.json", pack.packId, "案例包没有任何测量记录", "")
        if (pack.curves.isEmpty()) diags += ImportDiagnostic(
            ImportDiagnostic.Severity.REJECTED, "manifest.json", pack.packId, "案例包没有校准曲线", "")
        for (m in pack.measurements) {
            if (m.age.unit.isBlank() || m.sigma.unit.isBlank()) diags += ImportDiagnostic(
                ImportDiagnostic.Severity.REJECTED, "manifest.json", m.id, "测量缺少单位声明", "${m.age.raw} / ${m.sigma.raw}")
            if (m.source.isBlank()) diags += ImportDiagnostic(
                ImportDiagnostic.Severity.REJECTED, "manifest.json", m.id, "测量缺少来源", m.id)
        }
        for (c in pack.curves) if (c.source.isBlank()) diags += ImportDiagnostic(
            ImportDiagnostic.Severity.REJECTED, "manifest.json", c.id, "曲线缺少来源", c.file)
        for (o in pack.offsets) {
            if (o.source.isBlank()) diags += ImportDiagnostic(
                ImportDiagnostic.Severity.REJECTED, "manifest.json", o.id, "偏移分布缺少来源", o.id)
            if (o.components.none { it.weight > 0 }) diags += ImportDiagnostic(
                ImportDiagnostic.Severity.REJECTED, "manifest.json", o.id, "偏移分布没有正权重分量", o.id)
        }
        for (e in pack.sequence) if (e.older == e.younger) diags += ImportDiagnostic(
            ImportDiagnostic.Severity.REJECTED, "manifest.json", e.id, "层序边不能自指", "${e.older}->${e.younger}")
    }

    fun parseCurve(ref: RawCurveRef, dir: File): ParsedCurveFile {
        val diags = mutableListOf<ImportDiagnostic>()
        val file = File(dir, ref.file)
        if (!file.isFile) {
            return ParsedCurveFile(ref, emptyList(), listOf(ImportDiagnostic(
                ImportDiagnostic.Severity.REJECTED, ref.file, ref.id, "曲线文件不存在", "")))
        }
        val points = mutableListOf<Triple<Double, Double, Double>>()
        val lines = file.readLines().filter { it.isNotBlank() }
        lines.drop(1).forEachIndexed { index, line ->
            val parts = line.split(",", ";", "\t").map { it.trim() }
            val lineNo = index + 2
            if (parts.size != 3) {
                diags += ImportDiagnostic(ImportDiagnostic.Severity.REJECTED, ref.file, ref.id,
                    "第 $lineNo 行应为 calBP,14C_age_BP,sigma 三列", line)
                return@forEachIndexed
            }
            val nums = parts.map { it.toDoubleOrNull() }
            if (nums.any { it == null }) {
                diags += ImportDiagnostic(ImportDiagnostic.Severity.REJECTED, ref.file, ref.id,
                    "第 $lineNo 行存在不可解析数值（保留原始行，未删除）", line)
                return@forEachIndexed
            }
            val (bp, age, sigma) = nums.map { it!! }
            if (sigma <= 0) {
                diags += ImportDiagnostic(ImportDiagnostic.Severity.REJECTED, ref.file, ref.id,
                    "第 $lineNo 行 sigma 必须为正", line)
                return@forEachIndexed
            }
            points += Triple(bp, age, sigma)
        }
        if (points.zipWithNext().any { (a, b) -> a.first >= b.first }) {
            diags += ImportDiagnostic(ImportDiagnostic.Severity.REJECTED, ref.file, ref.id,
                "曲线 calBP 必须严格升序", "")
        }
        return ParsedCurveFile(ref, points, diags)
    }

    fun buildCurve(parsed: ParsedCurveFile): CalibrationCurve? {
        val pts = parsed.pointsText
        if (pts.isEmpty()) return null
        return CalibrationCurve(
            curveId = parsed.ref.id,
            version = parsed.ref.version,
            parentVersion = parsed.ref.parentVersion,
            points = pts.map { CurvePoint(it.first, it.second, it.third) },
            source = parsed.ref.source,
            notes = parsed.ref.notes,
        )
    }

    fun toMeasure(raw: RawMeasure, diagnosticSink: MutableList<ImportDiagnostic>, objectId: String): Measure {
        val value = raw.raw.toDoubleOrNull()
        val unitFactor = knownUnits[raw.unit]
        return when {
            value == null -> {
                diagnosticSink += ImportDiagnostic(ImportDiagnostic.Severity.UNCONVERTED, "manifest.json", objectId,
                    "量值无法转换为数值，保留原始表示：${raw.raw}", raw.raw)
                Measure(raw.raw, raw.unit, null, "不可解析数值，未参与派生计算")
            }
            raw.unit.isBlank() -> {
                diagnosticSink += ImportDiagnostic(ImportDiagnostic.Severity.UNCONVERTED, "manifest.json", objectId,
                    "缺少单位，保留原始表示", raw.raw)
                Measure(raw.raw, raw.unit, null, "缺少单位")
            }
            unitFactor == null -> {
                diagnosticSink += ImportDiagnostic(ImportDiagnostic.Severity.UNCONVERTED, "manifest.json", objectId,
                    "单位「${raw.unit}」不是 14C 年龄单位（疑似日历单位），保留原位，不混入校准", raw.raw)
                Measure(raw.raw, raw.unit, null, "单位不可换算到 14C yr BP")
            }
            else -> Measure(raw.raw, raw.unit, value * unitFactor, "按 14C yr BP 规范化")
        }
    }

    fun toEra(raw: RawEraSystem): EraSystem = EraSystem(raw.id, raw.name, raw.datum, raw.source)

    fun toOffsetComponents(raw: List<RawOffsetComponent>): List<OffsetComponent> =
        raw.map { OffsetComponent(it.deltaR, it.sigma, it.weight, it.basis) }
}

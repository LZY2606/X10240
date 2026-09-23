package gsb.persist

import gsb.domain.*
import java.io.File

/**
 * 仓库自带离线案例包：“碳年候选席：校准曲线、储库偏移与多峰年代证据”。
 * 不依赖公网；曲线为合成 IntCal20 风格数据，目录中显式标注。
 */
object CasePack {
    const val CASE_PROJECT_ID = "case-carbon-seats-v1"

    fun resourceText(path: String): String =
        javaClass.getResourceAsStream("/$path")?.bufferedReader()?.use { it.readText() }
            ?: error("bundled resource not found: $path")

    fun curveFromTsv(id: String, label: String, version: String, tsv: String,
                     source: SourceRef, supersedes: String? = null): CalibrationCurve {
        val points = tsv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.lowercase().startsWith("calbp") }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                require(parts.size >= 3) { "bad curve row: $line" }
                CurvePoint(
                    calBp = parts[0].toInt(),
                    c14Bp = parts[1].toDouble(),
                    sigma = parts[2].toDouble(),
                    source = source
                )
            }.toList().sortedBy { it.calBp }
        return CalibrationCurve(id, label, version, points, source, supersedes)
    }

    fun parseCaseJson(text: String, now: String): CaseImporter.ImportedCase =
        CaseImporter.parse(text, now)

    /** 将导入案例落到一个新项目（幂等：调用方负责先检查）。 */
    fun materialize(service: ProjectService, imported: CaseImporter.ImportedCase,
                    activeCurveId: String? = null) {
        val project = imported.project.copy(activeCurveId = activeCurveId)
        service.createProject(project)
        imported.curves.forEach { service.registerCurve(project.id, it) }
        if (activeCurveId != null) service.assignCurve(project.id, activeCurveId, actor = "case-pack")
        imported.reservoirs.forEach { service.submitReservoir(project.id, it) }
        imported.measurements.forEach { service.submitMeasurement(project.id, it) }
        imported.sequences.forEach { service.submitSequence(project.id, it) }
        imported.exclusions.forEach { service.submitExclusion(project.id, it) }
    }
}

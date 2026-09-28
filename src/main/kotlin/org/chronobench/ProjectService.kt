package org.chronobench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 导入格式错误：定位到具体字段，绝不静默丢弃。 */
class ImportException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

/**
 * 项目服务：领域工作的主编排。
 * - 来源一经提交不可覆盖；修订 = 新版本 + 父版本链（SOURCE_SUBMITTED/SOURCE_SUPERSEDED）。
 * - 派生结果带算法版本、参数指纹、直接来源；局部源改动只令依赖它的结果变 stale。
 * - 事件追加式；asOf(seq) 回放当时的来源版本、派生结论与决定，不被最新结果覆盖。
 */
class ProjectService(val db: Database, private val clock: Clock = SystemClock()) {
    val json: Json get() = db.json

    // ---------------- project ----------------
    fun createProject(name: String, description: String?, actor: String = "system"): Project {
        require(name.isNotBlank()) { "项目名不能为空" }
        val id = "P-" + IdGen.token()
        val p = Project(id, name, clock.nowMillis(), description)
        db.createProject(p)
        val at = clock.nowMillis()
        db.appendEvent(EventRecord(0, IdGen.event(id, 1), id, "PROJECT_CREATED", actor, at,
            targets = listOf(id), summary = "创建项目 $name"))
        return p
    }

    fun listProjects() = db.listProjects()
    fun getProject(id: String) = db.getProject(id)

    // ---------------- source submission ----------------
    fun submitSource(
        projectId: String,
        kind: SourceKind,
        explicitId: String?,
        payloadEl: JsonElement,
        provenance: Provenance,
        actor: String,
        validate: Boolean = true
    ): SourceVersion {
        val normalized = if (validate) normalizePayload(kind, payloadEl) else payloadEl
        val id = explicitId ?: allocateId(projectId, kind)
        val prev = latestVersion(projectId, id)
        val version = (prev?.version ?: 0) + 1
        val parent = prev?.let { "$id@v${it.version}" }
        val v = SourceVersion(
            id = id, projectId = projectId, kind = kind, version = version, parentId = parent,
            payload = normalized, submittedAt = clock.nowMillis(), submittedBy = actor,
            superseded = false, provenance = provenance
        )
        db.insertSource(v)
        if (prev != null) db.markSuperseded(id, prev.version)
        val at = clock.nowMillis()
        val type = if (prev == null) "SOURCE_SUBMITTED" else "SOURCE_REVISED"
        db.appendEvent(EventRecord(0, IdGen.event(projectId, 0), projectId, type, actor, at,
            targets = listOf("$id@v$version"),
            inputs = listOfNotNull(parent),
            outputs = listOf("$id@v$version"),
            detail = json.parseToJsonElement("""{"kind":"$kind","provenanceOrigin":"${provenance.origin}"}"""),
            summary = "${if (prev == null) "提交" else "修订"}来源 $id v$version (${kind.cn()})"))
        return v
    }

    private fun normalizePayload(kind: SourceKind, el: JsonElement): JsonElement = when (kind) {
        SourceKind.CURVE -> validateCurve(el)
        SourceKind.MEASUREMENT -> validateMeasurement(el)
        SourceKind.OFFSET_HYPOTHESIS -> validateOffset(el)
        SourceKind.ERA_SYSTEM -> validateEra(el)
        SourceKind.SEQUENCE_LINK -> validateLink(el)
        SourceKind.EXCLUSION -> validateExclusion(el)
    }

    private fun validateCurve(el: JsonElement): JsonElement {
        val errors = mutableListOf<String>()
        runCatching { json.decodeFromJsonElement(CurvePayload.serializer(), el) }
            .onFailure { errors += "CURVE 结构错误: ${it.message}" }
            .onSuccess { c ->
                if (c.knots.size < 2) errors += "CURVE 至少需要两个 knot"
                val sorted = c.knots.sortedBy { it.calBP }
                for (i in 1 until sorted.size) {
                    if (sorted[i].calBP == sorted[i - 1].calBP) errors += "CURVE knot calBP 重复: ${sorted[i].calBP}"
                    if (sorted[i].sigma <= 0) errors += "CURVE knot sigma 必须为正 (calBP=${sorted[i].calBP})"
                }
                if (c.xUnit !in setOf("calBP-years")) errors += "CURVE xUnit 必须为 calBP-years，得到 '${c.xUnit}'"
                if (c.yUnit !in setOf("BP-14C-years")) errors += "CURVE yUnit 必须为 BP-14C-years，得到 '${c.yUnit}'"
            }
        if (errors.isNotEmpty()) throw ImportException(errors)
        return el
    }

    private fun validateMeasurement(el: JsonElement): JsonElement {
        val errors = mutableListOf<String>()
        runCatching { json.decodeFromJsonElement(MeasurementPayload.serializer(), el) }
            .onFailure { errors += "MEASUREMENT 结构错误: ${it.message}" }
            .onSuccess { m ->
                if (m.labCode.isBlank()) errors += "MEASUREMENT labCode 为空"
                if (!m.age.convertible) errors += "MEASUREMENT ${m.labCode}: 年龄无法规范化 (${m.age.diagnostics.joinToString()})；保留原始值但不可校准"
                if (!m.sigma.convertible) errors += "MEASUREMENT ${m.labCode}: 标准差无法规范化 (${m.sigma.diagnostics.joinToString()})"
                if (m.curveId.isBlank()) errors += "MEASUREMENT ${m.labCode}: 未指定 curveId"
            }
        if (errors.isNotEmpty()) throw ImportException(errors)
        return el
    }

    private fun validateOffset(el: JsonElement): JsonElement {
        val errors = mutableListOf<String>()
        runCatching { json.decodeFromJsonElement(OffsetHypothesisPayload.serializer(), el) }
            .onFailure { errors += "OFFSET_HYPOTHESIS 结构错误: ${it.message}" }
            .onSuccess { o ->
                if (o.label.isBlank()) errors += "OFFSET_HYPOTHESIS label 为空"
                if (!o.mean.convertible) errors += "OFFSET ${o.label}: mean 无法规范化（原始值保留）"
                if (!o.sigma.convertible) errors += "OFFSET ${o.label}: sigma 无法规范化（原始值保留）"
                if (o.distribution !in setOf("gaussian")) errors += "OFFSET ${o.label}: 仅支持 gaussian 分布，得到 '${o.distribution}'"
            }
        if (errors.isNotEmpty()) throw ImportException(errors)
        return el
    }

    private fun validateEra(el: JsonElement): JsonElement {
        val errors = mutableListOf<String>()
        runCatching { json.decodeFromJsonElement(EraSystemPayload.serializer(), el) }
            .onFailure { errors += "ERA_SYSTEM 结构错误: ${it.message}" }
            .onSuccess { e ->
                if (e.zeroConvention != "astronomical_year_zero")
                    errors += "ERA_SYSTEM zeroConvention 必须为 astronomical_year_zero（含 0=1 BCE），得到 '${e.zeroConvention}'"
            }
        if (errors.isNotEmpty()) throw ImportException(errors)
        return el
    }

    private fun validateLink(el: JsonElement): JsonElement {
        val errors = mutableListOf<String>()
        runCatching { json.decodeFromJsonElement(SequenceLinkPayload.serializer(), el) }
            .onFailure { errors += "SEQUENCE_LINK 结构错误: ${it.message}" }
            .onSuccess { l ->
                if (l.olderSampleCode == l.youngerSampleCode) errors += "SEQUENCE_LINK ${l.label}: 自指约束"
                if (l.relation !in setOf("older_than", "same_layer"))
                    errors += "SEQUENCE_LINK ${l.label}: 未知 relation '${l.relation}'"
            }
        if (errors.isNotEmpty()) throw ImportException(errors)
        return el
    }

    private fun validateExclusion(el: JsonElement): JsonElement {
        val errors = mutableListOf<String>()
        runCatching { json.decodeFromJsonElement(ExclusionPayload.serializer(), el) }
            .onFailure { errors += "EXCLUSION 结构错误: ${it.message}" }
            .onSuccess { x -> if (x.reason.isBlank()) errors += "EXCLUSION ${x.label}: 必须给出排除理由（来源可追溯）" }
        if (errors.isNotEmpty()) throw ImportException(errors)
        return el
    }

    private fun allocateId(projectId: String, kind: SourceKind): String {
        val existing = db.listSourceVersions(projectId).filter { it.kind == kind }.map { it.id }.toSet()
        val prefix = when (kind) {
            SourceKind.CURVE -> "C"
            SourceKind.MEASUREMENT -> "M"
            SourceKind.OFFSET_HYPOTHESIS -> "H"
            SourceKind.ERA_SYSTEM -> "ERA"
            SourceKind.SEQUENCE_LINK -> "L"
            SourceKind.EXCLUSION -> "X"
        }
        var n = 1
        while ("$prefix${"%02d".format(n)}" in existing) n++
        return "$prefix${"%02d".format(n)}"
    }

    // ---------------- read helpers ----------------
    fun latestVersion(projectId: String, id: String): SourceVersion? =
        db.listSourceVersions(projectId).filter { it.id == id }.maxByOrNull { it.version }

    fun latestByKind(projectId: String, kind: SourceKind): List<SourceVersion> =
        db.listSourceVersions(projectId).filter { it.kind == kind }
            .groupBy { it.id }.mapValues { it.value.maxBy { v -> v.version } }.values.toList()

    fun allSources(projectId: String) = db.listSourceVersions(projectId)

    fun <T> payload(v: SourceVersion, serializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, v.payload)
    fun <T> payloadOrNull(v: SourceVersion?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        v?.let { runCatching { payload(it, serializer) }.getOrNull() }
}

fun SourceKind.cn(): String = when (this) {
    SourceKind.CURVE -> "校准曲线"
    SourceKind.MEASUREMENT -> "放射性碳测量"
    SourceKind.OFFSET_HYPOTHESIS -> "储库偏移假设"
    SourceKind.ERA_SYSTEM -> "年代系统"
    SourceKind.SEQUENCE_LINK -> "层序约束"
    SourceKind.EXCLUSION -> "排除决定"
}

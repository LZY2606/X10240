package org.chronobench

/**
 * 时间旅行：在事件 seq 处回放项目状态。使用“当时已存在的来源版本”和“当时已产生的派生结果”，
 * 并叠加“截至该事件已生效且未被撤销”的人工决定。绝不拿最新结果覆盖历史。
 */
class TimelineService(val projects: ProjectService, val review: ReviewService) {
    private val db get() = projects.db

    @kotlinx.serialization.Serializable
    data class StateSnapshot(
        val atSeq: Long,
        val projectId: String,
        val event: EventRecord,
        val sources: List<SourceVersion>,
        val calibrations: List<CalibrationResult>,
        val sequences: List<SequenceResult>,
        val activeDecisions: Map<String, String>,
        val events: List<EventRecord>
    )

    fun events(projectId: String) = db.listEvents(projectId)

    fun stateAt(projectId: String, seq: Long): StateSnapshot? {
        val ev = db.eventAt(projectId, seq) ?: return null

        // 截至 seq，每个 id 的最大版本（依据 SOURCE_* 事件，而非当前表状态）
        val sourceVersions = HashMap<String, Int>()
        val events = db.listEvents(projectId, seq)
        for (e in events) {
            val t = e.targets.firstOrNull { it.contains("@v") } ?: continue
            if (e.type in setOf("SOURCE_SUBMITTED", "SOURCE_REVISED")) {
                val (id, ver) = parseRef(t)
                sourceVersions[id] = maxOf(sourceVersions[id] ?: 0, ver)
            }
        }
        val sources = sourceVersions.mapNotNull { (id, ver) -> db.loadSource(id, ver) }
            .filter { it.projectId == projectId }
            .sortedWith(compareBy({ it.kind.name }, { it.id }, { it.version }))

        // 截至 seq 已产生的派生结果
        val derivedIds = events.filter {
            it.type in setOf("CALIBRATION_DERIVED", "SEQUENCE_ASSESSED", "SEQUENCE_CONFLICT_DETECTED")
        }.flatMap { it.outputs }.toSet()

        val decisions = HashMap<String, String>()
        for (e in events) {
            when (e.type) {
                "INTERVAL_ACCEPTED" -> e.targets.firstOrNull()?.let { decisions[it] = "ACCEPTED" }
                "INTERVAL_REJECTED" -> e.targets.firstOrNull()?.let { decisions[it] = "REJECTED" }
                "DECISION_REVERTED" -> e.targets.firstOrNull()?.let { decisions.remove(it) }
            }
        }

        val cals = review.allCalibrationDerivations(projectId)
            .filter { it.derivationId in derivedIds }
            .map { r -> applyHistoricalDecisions(r, decisions) }
            .sortedBy { it.computedAt }
        val seqs = db.listDerivations(projectId, "SEQUENCE")
            .map { projects.json.decodeFromString(SequenceResult.serializer(), it) }
            .filter { it.derivationId in derivedIds }
            .sortedBy { it.computedAt }

        return StateSnapshot(seq, projectId, ev, sources, cals, seqs, decisions, events)
    }

    private fun applyHistoricalDecisions(r: CalibrationResult, decisions: Map<String, String>): CalibrationResult {
        val ivs = r.intervals.map { iv ->
            val key = "${r.measurementId}/${iv.intervalId}"
            when (decisions[key]) {
                "REJECTED" -> iv.copy(excluded = true, exclusionReason = "历史裁定否定")
                else -> iv
            }
        }
        return r.copy(intervals = ivs)
    }

    private fun parseRef(ref: String): Pair<String, Int> {
        val at = ref.indexOf("@v")
        return ref.substring(0, at) to ref.substring(at + 2).toInt()
    }
}

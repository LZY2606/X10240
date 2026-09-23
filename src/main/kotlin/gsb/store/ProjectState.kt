package gsb.store

import gsb.model.*

/** Immutable view of the project at a point in the event stream.
 *  Derived results carry CURRENT / STALE; history views freeze status as decided. */
data class ProjectState(
    val projectId: String,
    val name: String,
    val curves: Map<String, CalibrationCurve>,
    val curveVersions: Map<String, List<String>>,   // curveId -> version ids newest last
    val measurements: Map<String, Measurement>,      // all versions, including superseded
    val currentMeasurementByCode: Map<String, String>,
    val offsets: Map<String, OffsetHypothesis>,
    val constraints: Map<String, SequenceConstraint>,
    val exclusions: Map<String, ExclusionDecision>,
    val results: Map<String, CalibrationResult>,
    val analyses: Map<String, SequenceAnalysis>,
    val reviews: List<ReviewDecision>,
    val sequence: List<String>                       // event ids in order
) {
    fun activeMeasurements(): List<Measurement> =
        currentMeasurementByCode.values.mapNotNull { measurements[it] }
            .filter { m -> (exclusions.values.find { it.measurementCode == m.code }?.status) != ExcludeStatus.EXCLUDED }
            .sortedBy { it.code }

    fun currentResults(): List<CalibrationResult> =
        results.values.filter { it.status == ResultStatus.CURRENT }

    fun resultFor(code: String): CalibrationResult? =
        results.values.filter { it.measurementCode == code }
            .sortedByDescending { it.virtualTime }.firstOrNull { it.status == ResultStatus.CURRENT }
}

object Fold {
    fun initial(id: String, name: String) = ProjectState(
        id, name, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
        emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList()
    )

    /** Fold the full (or prefix) stream. Staleness is derived only at the tip,
     *  so historical prefixes show exactly what was visible then. */
    fun fold(events: List<DomainEvent>, atTip: Boolean = true): ProjectState {
        var s = ProjectState("", "", emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList())
        val seqIds = mutableListOf<String>()
        for (e in events) {
            s = apply(s, e)
            seqIds.add(e.eventId)
        }
        s = s.copy(sequence = seqIds)
        return if (atTip) markStale(s) else s
    }

    @Suppress("UNCHECKED_CAST")
    private fun apply(s: ProjectState, e: DomainEvent): ProjectState = when (e) {
        is ProjectCreated -> s.copy(projectId = e.projectId, name = e.name)
        is CurveRegistered -> {
            // Curve ids already include the version, so curve objects are immutable.
            // curveVersions is keyed by the curve family and records order.
            val family = e.curve.id.substringBeforeLast("@v")
            val versions = (s.curveVersions[family] ?: emptyList()) + e.curve.id
            s.copy(curves = s.curves + (e.curve.id to e.curve),
                curveVersions = s.curveVersions + (family to versions.distinct()))
        }
        is MeasurementSubmitted -> {
            var meas = s.measurements
            var byCode = s.currentMeasurementByCode
            e.measurement.parentId?.let { pid ->
                val parent = meas[pid]
                if (parent != null) meas = meas + (pid to parent.copy(supersededBy = e.measurement.id))
                byCode = byCode + (e.measurement.code to e.measurement.id)
            }
            meas = meas + (e.measurement.id to e.measurement)
            byCode = byCode + (e.measurement.code to e.measurement.id)
            s.copy(measurements = meas, currentMeasurementByCode = byCode)
        }
        is OffsetSubmitted -> s.copy(offsets = s.offsets + (e.hypothesis.id to e.hypothesis))
        is ConstraintSubmitted -> s.copy(constraints = s.constraints + (e.constraint.id to e.constraint))
        is ExclusionRecorded -> s.copy(exclusions = s.exclusions + (e.decision.id to e.decision))
        is CalibrationComputed -> {
            // older results for same measurement+curve+offset become STALE on replacement
            val newR = e.result
            val results = s.results.mapValues { (_, r) ->
                if (r.id != newR.id && r.measurementId == newR.measurementId &&
                    r.offsetHypothesisId == newR.offsetHypothesisId)
                    r.copy(status = ResultStatus.STALE,
                        staleBecause = r.staleBecause + "replaced by ${newR.id}")
                else r
            } + (newR.id to newR)
            s.copy(results = results)
        }
        is SequenceAnalyzed -> {
            val analyses = s.analyses.mapValues { (_, a) ->
                if (a.constraintId == e.analysis.constraintId)
                    a.copy(status = ResultStatus.STALE,
                        staleBecause = a.staleBecause + "re-analysed by ${e.analysis.id}")
                else a
            } + (e.analysis.id to e.analysis)
            s.copy(analyses = analyses)
        }
        is ReviewRecorded -> s.copy(reviews = s.reviews + e.decision)
        is DecisionUndone -> {
            val reviews = s.reviews.map { d ->
                if (d.id == e.undoneDecisionId) d.copy(undoneBy = e.eventId) else d
            }
            s.copy(reviews = reviews)
        }
    }

    /** Local invalidation: a result is STALE only if a direct input moved
     *  (measurement superseded, curve version upgraded, offset replaced). */
    private fun markStale(s: ProjectState): ProjectState {
        val results = s.results.mapValues { (_, r) ->
            val reasons = mutableListOf<String>()
            val m = s.measurements[r.measurementId]
            if (m == null) reasons += "measurement missing"
            else if (s.currentMeasurementByCode[m.code] != m.id)
                reasons += "measurement ${m.code} revised by a newer version"
            val curve = s.curves[r.curveId]
            if (curve == null) reasons += "curve version ${r.curveVersion} no longer registered"
            else {
                val family = curve.id.substringBeforeLast("@v")
                val newest = s.curveVersions[family]?.lastOrNull()
                if (newest != null && newest != curve.id) {
                    val nv = s.curves[newest]?.version
                    reasons += "curve upgraded ${curve.version} -> $nv"
                }
            }
            if (!s.offsets.containsKey(r.offsetHypothesisId))
                reasons += "offset hypothesis removed"
            if (reasons.isNotEmpty() && r.status == ResultStatus.CURRENT)
                r.copy(status = ResultStatus.STALE, staleBecause = reasons)
            else r
        }
        val analyses = s.analyses.mapValues { (_, a) ->
            val reasons = a.staleBecause.toMutableList()
            listOf(a.youngerResultId, a.olderResultId).forEach { rid ->
                val r = results[rid]
                if (r != null && r.status == ResultStatus.STALE)
                    reasons += "depends on stale $rid"
            }
            if (reasons.any { it.startsWith("depends on stale") } && a.status == ResultStatus.CURRENT)
                a.copy(status = ResultStatus.STALE, staleBecause = reasons.distinct())
            else a.copy(staleBecause = reasons.distinct())
        }
        return s.copy(results = results, analyses = analyses)
    }
}

package c14.domain

import kotlinx.serialization.Serializable

@Serializable
data class ResultView(
    val result: CalibrationResult,
    val stale: Boolean,
    val staleReason: String?,
    val excludedSample: Boolean
)

@Serializable
data class DecisionConflict(val a: DecisionView, val b: DecisionView, val subject: String)

@Serializable
data class ProjectSnapshot(
    val projectId: String,
    val title: String,
    val eraSystem: String,
    val notes: String,
    val eventCount: Int,
    val sources: List<Source>,
    val curves: List<CalibrationCurve>,
    val curveFamilies: Map<String, String>,
    val offsets: List<OffsetModel>,
    val measurements: List<Measurement>,
    val assumptions: Map<String, Assumption>,
    val links: List<SequenceLink>,
    val exclusions: List<Exclusion>,
    val decisions: List<DecisionView>,
    val activeDecisions: Map<String, DecisionView>,
    val conflicts: List<DecisionConflict>,
    val acceptedIntervals: Set<String>,
    val rejectedIntervals: Set<String>,
    val results: List<ResultView>,
    val latestAnalyses: List<SequenceAnalysis>,
    val allAnalyses: List<SequenceAnalysis>
) {
    fun currentMeasurement(sampleId: String): Measurement? =
        measurements.filter { it.sampleId == sampleId }.maxByOrNull { m ->
            measurements.indexOfLast { it.id == m.id }
        }

    fun latestResult(sampleId: String): CalibrationResult? =
        results.filter { it.result.sampleId == sampleId && !it.excludedSample }
            .maxByOrNull { it.result.eventSeq }?.result

    val latestCurve: CalibrationCurve? get() = curves.maxByOrNull { c -> curves.indexOf(c) }

    fun currentCurveFor(sampleId: String): CalibrationCurve? {
        val used = latestResult(sampleId)?.curveId ?: return latestCurve
        val family = curves.firstOrNull { it.id == used }?.name ?: return curves.firstOrNull { it.id == used }
        return curves.filter { it.name == family }.maxByOrNull { it.eventOrderIndex() }
    }

    private fun CalibrationCurve.eventOrderIndex(): Int = curves.indexOf(this)

    fun samplesCalibratedWith(curveId: String): List<String> {
        val family = curves.firstOrNull { it.id == curveId }?.name ?: return emptyList()
        return results.map { it.result.sampleId }.distinct().filter { sample ->
            results.filter { it.result.sampleId == sample }
                .maxByOrNull { it.result.eventSeq }
                ?.let { rv -> curves.firstOrNull { c -> c.id == rv.result.curveId }?.name == family } == true
        }
    }
}

/** Replays the event log up to a chosen sequence number. */
class SnapshotBuilder(
    private val log: List<Event>,
    private val calibrator: Calibrator = Calibrator()
) {
    fun build(): ProjectSnapshot {
        var projectId = ""
        var title = ""
        var eraSystem = "BP/BCE-CE astronomical"
        var notes = ""
        val sources = linkedMapOf<String, Source>()
        val curves = linkedMapOf<String, CalibrationCurve>()
        val offsets = linkedMapOf<String, OffsetModel>()
        val measurements = linkedMapOf<String, Measurement>()
        val assumptions = linkedMapOf<String, Assumption>()
        val links = linkedMapOf<String, SequenceLink>()
        val exclusions = linkedMapOf<String, Exclusion>()
        val decisionsList = mutableListOf<DecisionView>()
        val resultsList = mutableListOf<CalibrationResult>()
        val analysesList = mutableListOf<SequenceAnalysis>()

        for (e in log) {
            when (e.type) {
                "PROJECT_STARTED" -> domainJson.decodeFromString<ProjectStarted>(e.payloadJson).let {
                    projectId = it.projectId; title = it.title; eraSystem = it.eraSystem; notes = it.notes
                }
                "SOURCE_REGISTERED" -> domainJson.decodeFromString<SourceRegistered>(e.payloadJson).let { sources[it.source.id] = it.source }
                "CURVE_REGISTERED" -> domainJson.decodeFromString<CurveRegistered>(e.payloadJson).let { curves[it.curve.id] = it.curve }
                "OFFSET_MODEL_REGISTERED" -> domainJson.decodeFromString<OffsetModelRegistered>(e.payloadJson).let { offsets[it.offset.id] = it.offset }
                "MEASUREMENT_SUBMITTED" -> domainJson.decodeFromString<MeasurementSubmitted>(e.payloadJson).let { measurements[it.measurement.id] = it.measurement }
                "ASSUMPTION_SET" -> domainJson.decodeFromString<AssumptionSet>(e.payloadJson).let {
                    assumptions[it.sampleId] = Assumption(it.sampleId, it.offsetId, e.seq, it.reason, it.sourceId)
                }
                "LINK_DECLARED" -> domainJson.decodeFromString<LinkDeclared>(e.payloadJson).let { links[it.link.id] = it.link }
                "EXCLUSION_DECIDED" -> domainJson.decodeFromString<ExclusionDecided>(e.payloadJson).let { exclusions[it.exclusion.sampleId] = it.exclusion.copy(eventSeq = e.seq) }
                "DECISION_RECORDED" -> domainJson.decodeFromString<DecisionRecorded>(e.payloadJson).let { decisionsList += it.decision.copy(eventSeq = e.seq) }
                "CALIBRATION_ISSUED" -> domainJson.decodeFromString<CalibrationIssued>(e.payloadJson).let { resultsList += it.result }
                "SEQUENCE_ANALYSIS_ISSUED" -> domainJson.decodeFromString<SequenceAnalysisIssued>(e.payloadJson).let { analysesList += it.analysis }
                else -> Unit
            }
        }

        // Active decisions, honoring revocations and supersession.
        val active = LinkedHashMap<String, DecisionView>()
        for (d in decisionsList) {
            when {
                d.kind == "REVOKE" -> active.remove(d.subject)
                d.supersedesDecisionId != null -> active.remove(d.supersedesDecisionId)
            }
            if (d.kind != "REVOKE") active[d.id] = d
        }
        val accepted = active.values.filter { it.kind == "ACCEPT" }.map { it.subject }.toSet()
        val rejected = active.values.filter { it.kind == "REJECT_CALENDAR_INTERVAL" }.map { it.subject }.toSet()

        val conflicts = active.values
            .filter { it.kind == "ADJUST_PARAMETER" }
            .groupBy { it.subject }
            .flatMap { (subject, group) ->
                if (group.map { it.rationale }.distinct().size > 1)
                    group.zipWithNext().map { DecisionConflict(it.first, it.second, subject) }
                else emptyList()
            }

        // Families: curve name -> id of the newest version (replay order).
        val families = curves.values.groupBy { it.name }.mapValues { (_, list) -> list.last().id }

        val excludedSamples = exclusions.keys
        val resultViews = resultsList.map { r ->
            val reason = staleReason(r, measurements, curves, offsets, assumptions)
            ResultView(r, reason != null, reason, r.sampleId in excludedSamples)
        }

        val latestAnalysisPerLinkSet = analysesList.maxByOrNull { it.eventSeq }
        val latestAnalyses = latestAnalysisPerLinkSet?.let { listOf(it) } ?: emptyList()

        return ProjectSnapshot(
            projectId = projectId.ifEmpty { "(empty)" },
            title = title,
            eraSystem = eraSystem,
            notes = notes,
            eventCount = log.size,
            sources = sources.values.toList(),
            curves = curves.values.toList(),
            curveFamilies = families,
            offsets = offsets.values.toList(),
            measurements = measurements.values.toList(),
            assumptions = assumptions.toMap(),
            links = links.values.toList(),
            exclusions = exclusions.values.toList(),
            decisions = decisionsList,
            activeDecisions = active,
            conflicts = conflicts,
            acceptedIntervals = accepted,
            rejectedIntervals = rejected,
            results = resultViews,
            latestAnalyses = latestAnalyses,
            allAnalyses = analysesList
        )
    }

    private fun staleReason(
        r: CalibrationResult,
        measurements: Map<String, Measurement>,
        curves: Map<String, CalibrationCurve>,
        offsets: Map<String, OffsetModel>,
        assumptions: Map<String, Assumption>
    ): String? {
        val measurement = measurements[r.measurementId] ?: return "measurement missing"
        if (measurement.sampleId != r.sampleId) return "measurement sample mismatch"
        val family = curves[r.curveId]?.name
        val newestFamily = curves.values.filter { it.name == family }.maxByOrNull { curves.values.toList().indexOf(it) }
        if (newestFamily != null && newestFamily.id != r.curveId) {
            return "curve superseded by ${newestFamily.version} (${newestFamily.id})"
        }
        val currentMeasurement = measurements.values.filter { it.sampleId == r.sampleId }.toList()
        val newestMeasurement = currentMeasurement.lastOrNull()
        if (newestMeasurement != null && newestMeasurement.id != r.measurementId) {
            return "measurement revised by ${newestMeasurement.id}"
        }
        val assumedOffset = assumptions[r.sampleId]?.offsetId
        if (assumedOffset != r.offsetId) {
            return "offset hypothesis changed to ${assumedOffset ?: "none"}"
        }
        return null
    }
}

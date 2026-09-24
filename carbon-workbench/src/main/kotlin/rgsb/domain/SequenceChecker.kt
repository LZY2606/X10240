package rgsb.domain

import kotlinx.serialization.json.JsonPrimitive
import rgsb.model.CalibrationResult
import rgsb.model.CandidateAdjustment
import rgsb.model.SequenceLink
import rgsb.model.SequenceSpec
import rgsb.model.SequenceVerdict
import kotlin.math.max
import kotlin.math.min

/**
 * Stratigraphic order check. A link "a older-than b" is satisfied when there exists a
 * year x in a's 95% set and y in b's 95% set with x < y, and more strictly when the
 * posterior probability P(cal_a < cal_b) is high.
 *
 * Conflicts never rewrite measurements. They generate *candidate adjustments*: try a
 * different reservoir offset hypothesis, exclude a calendar range, or re-measure.
 * When multiple candidates remain legal, all are retained (non-unique resolution).
 */
class SequenceChecker(private val clockText: () -> String) {

    fun probabilityOlder(a: CalibrationResult, b: CalibrationResult): Double {
        // P(yearA < yearB) on the 1-year grids, merged by year.
        val byA = a.grid.associate { it.year to it.weight }
        val byB = b.grid.associate { it.year to it.weight }
        val years = (byA.keys + byB.keys).sorted()
        var cumB = 0.0
        var p = 0.0
        for (y in years) {
            val wa = byA[y] ?: 0.0
            val wb = byB[y] ?: 0.0
            p += wa * (cumB + 0.5 * wb) // tie year: half mass considered strictly later
            cumB += wb
        }
        return p
    }

    fun check(
        specs: List<SequenceSpec>,
        results: List<CalibrationResult>,
        availableOffsetIds: List<String>,
    ): List<SequenceVerdict> {
        val byId = results.associateBy { it.measurementId }
        val verdicts = mutableListOf<SequenceVerdict>()
        for (spec in specs) {
            for (link in spec.links) {
                val a = byId[link.before]
                val b = byId[link.after]
                if (a == null || b == null) {
                    verdicts += SequenceVerdict(
                        link = link,
                        satisfied = false,
                        evidence = "UNKNOWN: missing calibration result for ${if (a == null) link.before else link.after}",
                        candidateAdjustments = emptyList(),
                    )
                    continue
                }
                val p = probabilityOlder(a, b)
                val satisfied = p >= 0.90
                val cands = if (satisfied) emptyList() else candidateAdjustments(link, a, b, availableOffsetIds, p)
                verdicts += SequenceVerdict(
                    link = link,
                    satisfied = satisfied,
                    evidence = "P(%s older than %s)=%.4f over 95%% grids (%s vs %s; curves %s@%s, %s@%s)".format(
                        link.before, link.after, p,
                        summarizeRanges(a), summarizeRanges(b),
                        a.curveId, a.curveVersion, b.curveId, b.curveVersion,
                    ),
                    candidateAdjustments = cands,
                )
            }
        }
        return verdicts
    }

    private fun summarizeRanges(r: CalibrationResult): String {
        val hpd = r.intervals.filter { it.hpdLevel == 0.95 }
        return if (hpd.isEmpty()) "no-95%-support"
        else hpd.joinToString(",") { "${CalendarConventions.label(it.yearFrom)}..${CalendarConventions.label(it.yearTo)}" }
    }

    private fun candidateAdjustments(
        link: SequenceLink,
        a: CalibrationResult,
        b: CalibrationResult,
        availableOffsetIds: List<String>,
        p: Double,
    ): List<CandidateAdjustment> {
        val out = mutableListOf<CandidateAdjustment>()
        // 1) Every *other* offset hypothesis is a legal thing to try; the reviewer decides.
        for (offsetId in availableOffsetIds) {
            if (offsetId == a.offsetId && offsetId == b.offsetId) continue
            out += CandidateAdjustment(
                candidateId = "cand-${link.before}-${link.after}-offset-$offsetId-${clockText().hashCode()}",
                kind = "OFFSET_CHANGE",
                targetMeasurementId = link.before,
                proposal = JsonPrimitive(offsetId),
                rationale = "P(older)=%.3f below 0.90; recalibrate %s under offset hypothesis %s".format(p, link.before, offsetId),
                legal = true,
            )
        }
        // 2) Excluding the offending calendar overlap is a documented manual action.
        val overlapStart = max(earliest(a), earliest(b))
        val overlapEnd = min(latest(a), latest(b))
        if (overlapStart <= overlapEnd) {
            out += CandidateAdjustment(
                candidateId = "cand-${link.before}-${link.after}-exclude-${clockText().hashCode()}",
                kind = "EXCLUDE_RANGE",
                targetMeasurementId = link.after,
                proposal = JsonPrimitive("$overlapStart..$overlapEnd"),
                rationale = "stratigraphic overlap ${CalendarConventions.label(overlapStart)}..${CalendarConventions.label(overlapEnd)} can be excluded with a sourced exclusion decision",
                legal = true,
            )
        }
        // 3) Re-measurement is always legal and never silent.
        out += CandidateAdjustment(
            candidateId = "cand-${link.before}-${link.after}-remeasure-${clockText().hashCode()}",
            kind = "RE_MEASURE",
            targetMeasurementId = link.before,
            proposal = JsonPrimitive("request new 14C determination"),
            rationale = "sequence conflict cannot be uniquely resolved from current evidence",
            legal = true,
        )
        return out
    }

    private fun earliest(r: CalibrationResult) =
        r.intervals.filter { it.hpdLevel == 0.95 }.minOfOrNull { it.yearFrom } ?: r.grid.first().year
    private fun latest(r: CalibrationResult) =
        r.intervals.filter { it.hpdLevel == 0.95 }.maxOfOrNull { it.yearTo } ?: r.grid.last().year
}

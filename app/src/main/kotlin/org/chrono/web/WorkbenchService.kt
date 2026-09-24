package org.chrono.web

import org.chrono.calib.CalibrationEngine
import org.chrono.calib.CalibrationResult
import org.chrono.calib.SequenceChecker
import org.chrono.domain.*
import org.chrono.store.Clock
import org.chrono.store.EventStore
import org.chrono.store.StableIds

class WorkbenchService(val store: EventStore, val clock: Clock) {

    fun stateAt(seq: Long? = null): ProjectState = store.projectAt(seq)

    /** 当前（或历史 seq 下）派生视图；历史视图按当时事件重算，使用当时钉版，绝不拿最新结果覆盖。 */
    fun viewAt(seq: Long? = null): ProjectView {
        val stored = store.load(seq)
        val state = store.projectAt(seq)
        val entries = state.latestMeasurements().map { m ->
            val curve = state.curveFor(m)
            val restriction = state.restrictions[m.measurementId]
            if (curve == null) {
                ViewEntry(m, null, stale = true, listOf("curve ${m.curveId}@${m.curveVersion} missing"), restriction)
            } else {
                val raw = CalibrationEngine.calibrate(m, curve)
                val current = if (restriction != null) restrict(raw, restriction) else raw
                val reasons = staleReasons(m, raw, state)
                ViewEntry(m, current, reasons.isNotEmpty(), reasons, restriction)
            }
        }
        val resultMap = entries.mapNotNull { e -> e.current?.let { e.measurement.measurementId to it } }.toMap()
        val assessments = state.edges.values.map { SequenceChecker.assess(it, resultMap) }
        return ProjectView(state, entries, assessments, stored.lastOrNull()?.seq ?: 0L)
    }

    private fun restrict(r: CalibrationResult, res: Restriction): CalibrationResult {
        val cut = r.grid.map { gp ->
            val inside = gp.year in res.fromAstro..res.toAstro
            org.chrono.calib.GridPoint(gp.year, if (inside) gp.density else 0.0)
        }
        val mass = org.chrono.calib.GridMath.trapezoid(cut, res.fromAstro, res.toAstro)
        val renorm = if (mass > 0) cut.map { org.chrono.calib.GridPoint(it.year, it.density / mass) } else cut
        return r.copy(
            grid = renorm,
            mass = mass,
            peaks = org.chrono.calib.PeakFinder.run { peaks(renorm) },
            intervals95 = org.chrono.calib.Hpd.intervals(renorm, 0.95)
        )
    }

    private fun staleReasons(m: Measurement, fresh: CalibrationResult, state: ProjectState): List<String> {
        val reasons = ArrayList<String>()
        val newer = state.curves[m.curveId]?.keys?.filter { it != m.curveVersion } ?: emptyList()
        if (newer.isNotEmpty()) {
            val latestCurve = state.curves[m.curveId]!!.getValue(newer.max())
            val changed = latestCurve.changedCalendarRange(
                state.curves[m.curveId]!!.getValue(m.curveVersion)
            )
            val overlaps = changed != null &&
                !(changed.last < fresh.grid.first().year || changed.first > fresh.grid.last().year)
            if (overlaps) reasons += "curve ${m.curveId} upgraded to ${latestCurve.version}; changed calendar range overlaps support"
            else if (changed == null) reasons += "curve ${m.curveId} has newer version ${latestCurve.version} (no point changes)"
        }
        return reasons
    }

    fun append(e: DomainEvent): Long = store.append(e)

    fun adjudicate(edgeId: String, accept: Boolean, kind: String, target: String, payload: String, rejectReason: String?): Long {
        val now = clock.now().toString()
        val key = "$edgeId|$accept|$kind|$target|$payload|$rejectReason|$now"
        val ev = DomainEvent.ConflictAdjudicated(
            eventId = StableIds.typeCode("adj", key), projectId = currentProjectId(),
            edgeId = edgeId, accepted = accept, adjustmentKind = kind, targetMeasurementId = target,
            payloadJson = payload, rejectReason = rejectReason,
            actor = "reviewer", occurredAt = now, reason = rejectReason, sourceRef = "ui:review"
        )
        val seq = store.append(ev)
        if (accept) applyAcceptedAdjustment(edgeId, kind, target, payload)
        return seq
    }

    private fun applyAcceptedAdjustment(edgeId: String, kind: String, target: String, payload: String) {
        val now = clock.now().toString()
        when (kind) {
            "RestrictToOlderPeak" -> {
                val parts = payload.removeSurrounding("[", "]").split(",")
                val from = parts[0].trim().toDouble(); val to = parts[1].trim().toDouble()
                store.append(DomainEvent.IntervalRestrictionApplied(
                    eventId = StableIds.typeCode("ira", "$edgeId|$target|$from|$to|$now"),
                    projectId = currentProjectId(), measurementId = target,
                    fromAstro = from, toAstro = to, originEdgeId = edgeId,
                    actor = "reviewer", occurredAt = now, reason = "accepted candidate from conflict review",
                    sourceRef = "ui:review"
                ))
            }
            "ExcludeSample" -> {
                val m = stateAt().measurement(target) ?: return
                store.append(DomainEvent.ExclusionRecorded(
                    eventId = StableIds.typeCode("exc", "$target|$now"),
                    projectId = currentProjectId(),
                    decision = ExclusionDecision("dec-${target.take(6)}-${clock.now().epochSecond}",
                        target, true, "accepted exclusion candidate", now, "ui:review"),
                    actor = "reviewer", occurredAt = now, reason = null, sourceRef = "ui:review"
                ))
            }
            else -> Unit
        }
    }

    fun reverseExclusion(measurementId: String) {
        val now = clock.now().toString()
        store.append(DomainEvent.ExclusionReversed(
            eventId = StableIds.typeCode("exr", "$measurementId|$now"),
            projectId = currentProjectId(),
            decision = ExclusionDecision("dec-${measurementId.take(6)}-${clock.now().epochSecond}",
                measurementId, false, "reversed by reviewer", now, "ui:review"),
            actor = "reviewer", occurredAt = now, reason = "undo", sourceRef = "ui:review"
        ))
    }

    fun liftRestriction(measurementId: String, originEdgeId: String) {
        val now = clock.now().toString()
        store.append(DomainEvent.IntervalRestrictionLifted(
            eventId = StableIds.typeCode("irl", "$measurementId|$originEdgeId|$now"),
            projectId = currentProjectId(), measurementId = measurementId, originEdgeId = originEdgeId,
            actor = "reviewer", occurredAt = now, reason = "undo candidate", sourceRef = "ui:review"
        ))
    }

    fun pinToCurve(measurementId: String, toVersion: String) {
        val s = stateAt()
        val m = s.measurementId2(measurementId)
        requireNotNull(m)
        val now = clock.now().toString()
        store.append(DomainEvent.CurvePinChanged(
            eventId = StableIds.typeCode("pin", "$measurementId|${m.version}|$toVersion|$now"),
            projectId = currentProjectId(), measurementId = measurementId,
            measurementVersion = m.version, fromCurveVersion = m.curveVersion, toCurveVersion = toVersion,
            actor = "reviewer", occurredAt = now, reason = "rebased to new curve version", sourceRef = "ui:review"
        ))
    }

    private fun ProjectState.measurementId2(id: String) = measurement(id)
    private fun currentProjectId(): String = stateAt().projectId ?: "proj"
}

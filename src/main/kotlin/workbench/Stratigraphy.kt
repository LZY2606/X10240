package workbench

object Stratigraphy {

    /**
     * A constraint "older must be >= younger in calBP" conflicts with the calibrated
     * distributions when the older sample's 95.4% interval lies entirely at younger
     * (smaller calBP) ages than the younger sample's interval.
     */
    fun findConflicts(
        constraints: List<StratConstraint>,
        calibrations: Map<String, CalibrationPayload> // keyed by measurement streamId
    ): List<Triple<StratConstraint, CalInterval, CalInterval>> {
        val out = mutableListOf<Triple<StratConstraint, CalInterval, CalInterval>>()
        for (c in constraints) {
            val older = calibrations[c.olderStreamId] ?: continue
            val younger = calibrations[c.youngerStreamId] ?: continue
            val oIv = older.intervals.filter { it.level == 0.954 }.maxByOrNull { it.toBp } ?: continue
            val yIv = younger.intervals.filter { it.level == 0.954 }.minByOrNull { it.fromBp } ?: continue
            // older sample must allow larger calBP than younger sample
            if (oIv.toBp < yIv.fromBp) out += Triple(c, oIv, yIv)
        }
        return out
    }

    /**
     * For a conflict, produce ALL legal candidate adjustments (never rewrite the
     * measurement itself). Each candidate truncates one side's posterior support so
     * the ordering holds; when the choice is not unique, every candidate is kept.
     */
    fun candidatesFor(
        conflictId: String,
        constraint: StratConstraint,
        olderCal: CalibrationPayload,
        youngerCal: CalibrationPayload
    ): List<Pair<String, StratCandidatePayload>> {
        val oIv = olderCal.intervals.filter { it.level == 0.954 }.maxBy { it.toBp }
        val yIv = youngerCal.intervals.filter { it.level == 0.954 }.minBy { it.fromBp }
        val boundary = (oIv.toBp + yIv.fromBp) / 2
        return listOf(
            "TRUNCATE_OLDER" to StratCandidatePayload(
                conflictId = conflictId,
                strategy = "TRUNCATE_OLDER",
                description = "Restrict ${constraint.olderStreamId} posterior to calBP >= $boundary (older side of boundary); measurement unchanged",
                adjustedFromBp = boundary,
                adjustedToBp = olderCal.gridEndBp,
                targetStreamId = constraint.olderStreamId
            ),
            "TRUNCATE_YOUNGER" to StratCandidatePayload(
                conflictId = conflictId,
                strategy = "TRUNCATE_YOUNGER",
                description = "Restrict ${constraint.youngerStreamId} posterior to calBP <= $boundary (younger side of boundary); measurement unchanged",
                adjustedFromBp = youngerCal.gridStartBp,
                adjustedToBp = boundary,
                targetStreamId = constraint.youngerStreamId
            ),
            "JOINT_REWEIGHT" to StratCandidatePayload(
                conflictId = conflictId,
                strategy = "JOINT_REWEIGHT",
                description = "Reweight both posteriors jointly under ordering ${constraint.olderStreamId} >= ${constraint.youngerStreamId}; measurement unchanged",
                adjustedFromBp = boundary,
                adjustedToBp = boundary,
                targetStreamId = constraint.olderStreamId
            )
        )
    }
}

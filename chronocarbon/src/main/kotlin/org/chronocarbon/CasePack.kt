package org.chronocarbon

/**
 * Offline domain case pack: "碳年候选席：校准曲线、储库偏移与多峰年代证据".
 * Everything is synthetic and bundled; no network access.
 */
object CasePack {
    const val CURVE_V1 = "/org/chronocarbon/casepack/synth20-v1.json"
    const val CURVE_V2 = "/org/chronocarbon/casepack/synth20-v2.json"
    const val SAMPLES = "/org/chronocarbon/casepack/baseline-samples.jsonl"

    data class Chapter(val key: String, val title: String, val description: String, val run: (CarbonService) -> List<EventEnvelope>)

    fun loadCurve(resource: String): CalibrationCurve =
        InputParser.parseCurve(javaClass.getResourceAsStream(resource)!!.bufferedReader().readText())

    fun loadSamplesText(): String =
        javaClass.getResourceAsStream(SAMPLES)!!.bufferedReader().readText()

    /**
     * Baseline: project, curve v1, measurements (each sourced), reservoir hypothesis on C-103,
     * sequence constraints and the exclusion of the unconvertible C-106.
     */
    fun initialize(service: CarbonService, sampleSource: String = "casepack/baseline-samples.jsonl"): List<EventEnvelope> {
        val out = ArrayList<EventEnvelope>()
        out += service.createProject("碳年候选席 / carbon-seat case")
        out += service.registerCurve(loadCurve(CURVE_V1), setActive = true)

        val parsed = InputParser.parseMeasurementLines(loadSamplesText(), sampleSource)
        for (row in parsed) {
            out += service.submitMeasurement(row.measurement)
            row.offset?.let { offset ->
                out += service.submitOffsetHypothesis(
                    OffsetHypothesis(
                        id = "hyp-${row.measurement.id}-baseline",
                        measurementId = row.measurement.id,
                        name = "baseline reservoir correction",
                        offset = offset,
                        provenance = Provenance("casepack reservoir table", "casepack/README.md", "baseline")
                    )
                )
            }
            row.constraintTo?.let { younger ->
                out += service.submitConstraint(
                    SequenceConstraint(
                        id = "con-${row.measurement.id}-$younger",
                        olderMeasurementId = row.measurement.id,
                        youngerMeasurementId = younger,
                        rationale = "field stratigraphy: ${row.measurement.id} below $younger",
                        provenance = Provenance("stratigraphic log S/7", "casepack/README.md", "baseline")
                    )
                )
            }
            if (row.exclude == true) {
                out += service.recordDecision(
                    Decision(
                        id = "dec-${row.measurement.id}-exclude",
                        targetMeasurementId = row.measurement.id,
                        exclude = true,
                        reason = row.decisionReason ?: "excluded at baseline",
                        provenance = Provenance("reviewer intake checklist", "casepack/README.md", "baseline")
                    )
                )
            }
        }
        // Explicit reservoir distribution on the marine shell: competing local/offshore water masses.
        out += service.submitOffsetHypothesis(
            OffsetHypothesis(
                id = "hyp-C-103-marine",
                measurementId = "C-103",
                name = "marine reservoir mixture (inshore/offshore)",
                offset = OffsetDist.Mixture(
                    listOf(
                        OffsetDist.Component(mean = 40.0, sigma = 30.0, weight = 0.6),
                        OffsetDist.Component(mean = 90.0, sigma = 45.0, weight = 0.4)
                    )
                ),
                provenance = Provenance("local reservoir study ΔR-2025", "casepack/README.md", "mixture of two water masses")
            )
        )
        return out
    }

    /** Request the first calibration for every valid, non-excluded measurement. */
    fun requestInitialCalibrations(service: CarbonService): List<DerivationRecord> {
        val state = service.state()
        return state.measurements.values
            .filter { it.valid && !service.state().let { s -> s.decisions.values.any { d -> d.targetMeasurementId == it.id && d.exclude && d.active } } }
            .map { m ->
                service.requestCalibration(
                    CalibrationRequest(
                        measurementId = m.id,
                        curveVersion = state.activeCurveVersion!!,
                        offset = service.state().activeHypothesis(m.id)?.offset ?: OffsetDist.Zero
                    )
                )
            }
    }
}

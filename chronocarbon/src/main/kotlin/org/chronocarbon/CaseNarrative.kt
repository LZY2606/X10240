package org.chronocarbon

/**
 * Scripted narrative used both by the UI "run chapter" action and by automated tests.
 * Each step appends real events, so provenance and replay exercise the same code path as manual work.
 */
object CaseNarrative {

    data class StepResult(val label: String, val events: List<EventEnvelope> = emptyList(), val derivations: List<DerivationRecord> = emptyList())

    /** Chapter 1: parameter revision — replace the marine reservoir mixture by a single Gaussian ΔR. */
    fun reviseReservoir(service: CarbonService): StepResult {
        val events = service.submitOffsetHypothesis(
            OffsetHypothesis(
                id = "hyp-C-103-marine-rev1",
                measurementId = "C-103",
                name = "revised single Gaussian Delta R",
                offset = OffsetDist.Gaussian(mean = 60.0, sigma = 40.0),
                provenance = Provenance("reservoir reassessment memo ΔR-2026", "casepack/README.md", "parameter revision", null),
                parentId = "hyp-C-103-marine"
            )
        )
        val state = service.state()
        val derivation = service.requestCalibration(
            CalibrationRequest("C-103", state.activeCurveVersion!!, OffsetDist.Gaussian(60.0, 40.0))
        )
        return StepResult("parameter revision: C-103 reservoir mixture -> Gaussian 60±40", listOf(events), listOf(derivation))
    }

    /**
     * Chapter 2: local-only source change — re-report C-101's sigma as a new measurement version.
     * Only C-101 derivations become stale; C-102..C-105 stay current.
     */
    fun reviseMeasurementSigma(service: CarbonService): StepResult {
        val parent = service.state().measurements["C-101"]!!
        val revised = parent.copy(
            id = "C-101-v2",
            rawSigmaText = "26",
            sigma = 26.0,
            parentId = "C-101",
            provenance = Provenance("lab-sheet A/2026 rev.2", "casepack/README.md", "sigma re-reported 30 -> 26")
        )
        val events = service.submitMeasurement(revised)
        return StepResult("local source change: C-101 sigma 30 -> 26 (new version, parent retained)", listOf(events))
    }

    /** Chapter 3: run sequence analysis; conflicts produce candidates, never edit measurements. */
    fun analyze(service: CarbonService): Pair<StepResult, SequenceAnalysis> {
        val analysis = service.analyzeSequences(seed = 7321L)
        return StepResult("sequence analysis: contradiction yields legal adjustment candidates") to analysis
    }

    /**
     * Chapter 4: two mutually conflicting human decisions on the same C-101/C-105 conflict.
     * One reviewer rejects changing the reservoir; another excludes C-105 as intrusive.
     * Both stay on record with parent/decision lineage; nothing overwrites the lab value.
     */
    fun conflictingDecisions(
        service: CarbonService,
        changeOffsetCandidateId: String
    ): List<StepResult> {
        val rejected = service.resolveCandidate(
            candidateId = changeOffsetCandidateId,
            accept = false,
            rejectionReason = "reviewer A: reservoir evidence does not support a correction large enough to resolve ordering"
        )
        val exclusion = Decision(
            id = "dec-C-105-exclude-intrusion",
            targetMeasurementId = "C-105",
            exclude = true,
            reason = "reviewer B: C-105 treated as intrusive root/bioturbation; raw measurement retained",
            provenance = Provenance("conservation meeting minutes", "casepack/README.md", "conflicting human decision")
        )
        val excludeCandidate = service.state().candidates.values.first { it.kind == "add_exclusion" }
        val accepted = service.resolveCandidate(
            candidateId = excludeCandidate.id,
            accept = true,
            newExclusion = exclusion
        )
        return listOf(
            StepResult("decision 1: reject reservoir adjustment (reason retained)", rejected),
            StepResult("decision 2: accept exclusion candidate for C-105", accepted)
        )
    }

    /** Chapter 5: curve upgrade. Old v1 derivations remain viewable but are flagged stale. */
    fun upgradeCurve(service: CarbonService): StepResult {
        val newCurve = CasePack.loadCurve(CasePack.CURVE_V2)
        val event = service.upgradeCurve(newCurve, "curve revision: adjusted wiggle amplitudes near 1955 cal BP")
        return StepResult("curve upgrade synth20-v1 -> synth20-v2; v1 results preserved but stale", listOf(event))
    }
}

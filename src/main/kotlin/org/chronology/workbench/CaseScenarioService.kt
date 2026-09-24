package org.chronology.workbench

import kotlinx.serialization.json.JsonObject

class CaseScenarioService(private val work: WorkService, private val clock: FixedClock = FixedClock()) {
    private val calibrationEngine = CalibrationEngine(1.0)

    fun applyStage(stageId: String): EventRecord = when (stageId) {
        "stage-1-parameter-revision" -> reservoirRevision()
        "stage-2-local-curve-revision" -> localCurveRevision()
        "stage-3-conflicting-decisions" -> conflictingDecisions()
        else -> throw WorkException("UNKNOWN_STAGE", "未知案例阶段: $stageId")
    }

    private fun reservoirRevision(): EventRecord {
        val state = work.state()
        val old = decodeInput<ReservoirPayload>(state, "reservoir-mixture-v1")
        val revised = old.copy(
            versionId = "reservoir-mixture-v2",
            parentVersionId = old.versionId,
            name = "混合海洋储库偏移情景（海洋权重修订）",
            scenarios = listOf(
                old.scenarios[0].copy(weight = 0.15),
                old.scenarios[1].copy(weight = 0.75),
                old.scenarios[2].copy(weight = 0.10),
            ),
            provenance = Provenance("casepack://reservoir/mixture-v2", "储库专家复核后重新归一化权重", "reservoir-revision", "stage 1"),
        )
        work.submitReservoir(revised)
        val affected = listOf("sample-marine", "sample-multi")
        affected.forEach { sample -> createCalibrationRevision(sample, "reservoir-mixture-v2", "request-${sample}-v2-reservoir") }
        return work.markStage("stage-1-parameter-revision")
    }

    private fun localCurveRevision(): EventRecord {
        val state = work.state()
        val old = decodeInput<CurvePayload>(state, "curve-synthetic-intcal-like-v1")
        val revisedPoints = old.points.map { point ->
            if (point.calendarBP in 2020.0..2060.0) {
                val wiggle = 7.0 * kotlin.math.sin((point.calendarBP - 2020.0) * 2.0 * Math.PI / 8.0)
                point.copy(radiocarbonBP = point.radiocarbonBP + wiggle, sourceId = "curve-point-${point.calendarBP.toInt()}-v2")
            } else point
        }
        val revised = CurvePayload(
            old.curveId, "curve-synthetic-intcal-like-v2", old.versionId, old.name + " v2", revisedPoints,
            Provenance("casepack://curve-synthetic-intcal-like/v2", "局部曲线升版，仅 2020–2060 BP 点变化", "curve-revision", "stage 2"),
            changeStartBP = 2020.0, changeEndBP = 2060.0,
        )
        work.submitCurve(revised)
        createCalibrationRevision("sample-multi", "reservoir-mixture-v2", "request-sample-multi-v3-curve", "curve-synthetic-intcal-like-v2")
        return work.markStage("stage-2-local-curve-revision")
    }

    private fun conflictingDecisions(): EventRecord {
        val sequence = work.state().derived.values.first { it.kind == "sequence" && it.subjectId == "sequence-profile-a7" }
        val computed = workbenchJson.decodeFromString(SequenceComputation.serializer(), workbenchJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), sequence.payload))
        val force = computed.candidates.first { it.kind == "FORCE_RELATION" }.candidateId
        val relax = computed.candidates.first { it.kind == "RELAX_RELATION" }.candidateId
        work.decide(DecisionPayload("decision-force-relation-v1", "ACCEPT", "sequence-candidate", force, "sequence-profile-a7", "现场负责人认为层序关系应强制保留", "profile-a7-ordering"))
        work.decide(DecisionPayload("decision-relax-relation-v1", "ACCEPT", "sequence-candidate", relax, "sequence-profile-a7", "实验室复核认为测量重叠，关系应降为待证假设", "profile-a7-ordering"))
        return work.markStage("stage-3-conflicting-decisions")
    }

    private fun createCalibrationRevision(sampleId: String, reservoirVersionId: String, requestId: String, curveVersionId: String? = null) {
        val state = work.state()
        val measurement = state.inputHistory[sampleId]?.firstOrNull { it.kind == "measurement" } ?: error("missing measurement")
        val curve = curveVersionId ?: "curve-synthetic-intcal-like-v1"
        val request = CalibrationRequestPayload(requestId, sampleId, measurement.versionId, curve, reservoirVersionId, "era-ce-bce-no-year-zero-v1")
        work.calibrate(request, "case-scenario-stage")
    }

    private inline fun <reified T> decodeInput(state: ProjectState, versionId: String): T =
        workbenchJson.decodeFromJsonElement(kotlinx.serialization.serializer(), state.inputs.getValue(versionId).payload)
}

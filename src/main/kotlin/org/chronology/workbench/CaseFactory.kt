package org.chronology.workbench

import kotlin.math.PI
import kotlin.math.sin

class CaseFactory(private val clock: FixedClock = FixedClock()) {
    private val calibrationEngine = CalibrationEngine(1.0)
    private val sequenceEngine = SequenceEngine()

    fun initialBundle(): Bundle {
        val events = mutableListOf<EventRecord>()
        fun add(type: String, subject: String, version: String?, parent: String?, payload: kotlinx.serialization.json.JsonObject, sources: List<String>, algorithm: String? = null, fingerprint: String? = null) {
            val eventId = version ?: StableIds.stable("evt", listOf(type, subject, payload.toString(), sources.joinToString(",")))
            val event = EventRecord(eventId, events.size + 1L, clock.nowIso(), type, subject, version, parent, payload, sources, algorithm, fingerprint, 20260924L)
            events.add(event)
        }

        val projectId = "carbon-candidate-bench"
        add("PROJECT_CREATED", projectId, projectId, null, jsonObjectOf(
            "projectId" to projectId,
            "title" to "碳年候选席：校准曲线、储库偏移与多峰年代证据",
            "description" to "离线放射性碳校准、多峰日历区间、储库偏移与层序审阅案例",
            "coordinateDatum" to "CAL_BP_CE1950",
            "provenance" to Provenance("casepack://carbon-candidate-bench/project", "案例包项目说明", "casepack", "offline/casepack/carbon-candidate-bench.json"),
        ), listOf("casepack://carbon-candidate-bench/project"))

        val curveV1Id = "curve-synthetic-intcal-like-v1"
        val curveV1 = CurvePayload(
            curveId = "curve-synthetic-intcal-like", versionId = curveV1Id, name = "Synthetic IntCal-like Offline Curve v1",
            points = points(1900.0, 2300.0) { bp -> g(bp) },
            provenance = Provenance("casepack://curve-synthetic-intcal-like/v1", "离线合成 IntCal-like 曲线 v1", "curve-version", "case factory, fixed seed 20260924"),
        )
        add("CURVE_VERSION_SUBMITTED", curveV1.curveId, curveV1.versionId, null, encoded(curveV1), listOf(curveV1.provenance.sourceId))

        val era = EraSystemPayload("era-ce-bce-no-year-zero", "era-ce-bce-no-year-zero-v1", "CE/BCE no year zero", "civil year; 1950 BP = 1 BCE/CE boundary", 1950.0, "CAL_BP_CE1950", Provenance("casepack://era/ce-bce", "CE/BCE 无零年坐标规则", "era-system", "offline case"))
        add("ERA_SYSTEM_SUBMITTED", era.eraSystemId, era.versionId, null, encoded(era), listOf(era.provenance.sourceId))

        val reservoirId = "reservoir-mixture-v1"
        val reservoir = ReservoirPayload(
            reservoirId, reservoirId, name = "混合海洋储库偏移情景", scenarios = listOf(
                ReservoirScenarioPayload("reservoir-no-offset", 0.0, 15.0, 0.25, "陆地/无额外储库偏移"),
                ReservoirScenarioPayload("reservoir-marine-120", 120.0, 35.0, 0.60, "海洋碳库 +120 BP"),
                ReservoirScenarioPayload("reservoir-upwelling-240", 240.0, 45.0, 0.15, "上升流强储库 +240 BP"),
            ), provenance = Provenance("casepack://reservoir/mixture-v1", "团队储库偏移分布会议记录", "reservoir-distribution", "offline case, weights explicit"),
        )
        add("RESERVOIR_VERSION_SUBMITTED", reservoir.reservoirId, reservoir.versionId, null, encoded(reservoir), listOf(reservoir.provenance.sourceId))

        val measurements = listOf(
            measurement("sample-multi", "多峰木炭", 2040.0, 18.0),
            measurement("sample-boundary", "BCE/CE 跨界炭粒", 1948.0, 8.0),
            measurement("sample-marine", "混合海洋贝壳", 2100.0, 14.0),
            measurement("sample-younger", "年轻上层兽骨", 2000.0, 20.0),
            measurement("sample-older", "年老下层炭化种子", 2070.0, 15.0),
        )
        measurements.forEach { measurementPayload ->
            add("MEASUREMENT_VERSION_SUBMITTED", measurementPayload.sampleId, measurementPayload.versionId, null, encoded(measurementPayload), listOf(measurementPayload.provenance.sourceId))
        }
        val badMeasurement = MeasurementPayload(
            "measurement-bad", "sample-bad", "measurement-bad-v1", name = "无法转换的实验室转写",
            age14CBP = Quantity("两千零四十上下", null, "14C_BP_YEAR", "RADIOCARBON_BP", "无法从自然语言转换为数值"),
            sigma14CBP = Quantity("约二十", null, "14C_BP_YEAR", "RADIOCARBON_BP", "无法从自然语言转换为数值"),
            provenance = Provenance("casepack://sample-bad/lab-note", "保留原始转写的格式错误样本", "measurement", "import diagnostic sample"),
        )
        add("MEASUREMENT_VERSION_SUBMITTED", badMeasurement.sampleId, badMeasurement.versionId, null, encoded(badMeasurement), listOf(badMeasurement.provenance.sourceId))

        val exclusion = ExclusionPayload("exclusion-modern-v1", "exclusion-modern-v1", "排除现代污染段", 1899.0, 1939.0, rationale = "样本上下文不可能落在现代污染窗口", provenance = Provenance("casepack://exclusion/modern-pollution", "地层排除决定", "exclusion-decision", "offline case"))
        add("EXCLUSION_DECIDED", exclusion.exclusionId, exclusion.versionId, null, encoded(exclusion), listOf(exclusion.provenance.sourceId))

        val relationA = SequenceRelationPayload("relation-younger-older-v1", "relation-younger-older-v1", "sample-younger", "sample-older", "上层样本必须年轻于下层样本", Provenance("casepack://stratigraphy/younger-older", "发掘记录剖面 A-7", "sequence-constraint", "offline case"))
        add("SEQUENCE_RELATION_SUBMITTED", relationA.relationId, relationA.versionId, null, encoded(relationA), listOf(relationA.provenance.sourceId))

        val requestIds = measurements.map { "request-${it.sampleId}-v1" }
        val computations = measurements.mapIndexed { index, measurementPayload ->
            val requestId = requestIds[index]
            val request = CalibrationRequestPayload(requestId, measurementPayload.sampleId, measurementPayload.versionId, curveV1Id, reservoirId, era.versionId, if (measurementPayload.sampleId == "sample-boundary") listOf(exclusion.versionId) else emptyList())
            add("CALIBRATION_REQUESTED", requestId, requestId, null, jsonObjectOf("request" to encoded(request), "provenance" to Provenance("casepack://$requestId", "案例包初始校准请求", "request", "offline case")), listOf(measurementPayload.versionId, curveV1Id, reservoirId, era.versionId) + request.exclusionVersionIds)
            val computation = calibrationEngine.calibrate(
                measurementPayload.sampleId, requestId,
                measurementPayload.age14CBP.numericValue!!, measurementPayload.sigma14CBP.numericValue!!,
                InterpolatedCurve(curveV1.points), curveV1Id,
                reservoir.scenarios.map { ReservoirScenario(it.offsetBP, it.sigmaBP, it.weight, it.hypothesisId) },
                measurementPayload.versionId, reservoirId, request.exclusionVersionIds.map { exclusion.startBP to exclusion.endBP },
            )
            add("CALIBRATION_DERIVED", computation.subjectId, computation.versionId, null, encoded(computation), computation.directSources, ALGORITHM_CALIBRATION, computation.parameterFingerprint)
            computation
        }

        val sequenceRequest = SequenceRequestPayload("request-sequence-v1", "sequence-profile-a7", listOf(relationA.versionId), listOf(requestIds[3], requestIds[4]))
        add("SEQUENCE_EVALUATION_REQUESTED", sequenceRequest.requestId, sequenceRequest.requestId, null, jsonObjectOf("request" to encoded(sequenceRequest), "provenance" to Provenance("casepack://request-sequence-v1", "案例包初始层序评估", "request", "offline case")), sequenceRequest.relationVersionIds + sequenceRequest.calibrationRequestIds)
        val seqComputation = sequenceEngine.evaluate(sequenceRequest.sequenceId, sequenceRequest.requestId, listOf(SequenceRelationSpec(relationA.relationId, relationA.versionId, relationA.youngerSampleId, relationA.olderSampleId)), computations.associateBy { it.subjectId })
        add("SEQUENCE_DERIVED", seqComputation.subjectId, seqComputation.versionId, null, encoded(seqComputation), seqComputation.directSources, ALGORITHM_SEQUENCE, seqComputation.parameterFingerprint)

        return Bundle("radiocarbon-workbench-bundle-v1", ProjectInfo(projectId, "碳年候选席", "离线案例包", "CAL_BP_CE1950"), events)
    }

    fun stages(): List<CaseStage> = listOf(
        CaseStage("stage-1-parameter-revision", "修订储库权重", "将海洋储库权重从 0.60 调整为 0.75，并保留父版本"),
        CaseStage("stage-2-local-curve-revision", "局部曲线升版", "只修改 2020–2060 BP 曲线段，边界和海洋分布保持可并行审阅"),
        CaseStage("stage-3-conflicting-decisions", "提交互斥人工决定", "对同一冲突键接受两个并列候选，系统记录冲突而不覆盖测量"),
    )

    private fun measurement(id: String, name: String, age: Double, sigma: Double): MeasurementPayload {
        return MeasurementPayload(
            "measurement-$id", "sample-$id", "measurement-$id-v1", name = name,
            age14CBP = Quantity(age.toBigDecimal().toPlainString(), age, "14C_BP_YEAR", "RADIOCARBON_BP"),
            sigma14CBP = Quantity(sigma.toBigDecimal().toPlainString(), sigma, "14C_BP_YEAR", "RADIOCARBON_BP"),
            provenance = Provenance("casepack://$id/lab-report-v1", "${name}实验室报告单", "measurement", "offline case laboratory report"),
        )
    }

    private fun points(start: Double, end: Double, function: (Double) -> Double): List<CurvePoint> {
        return (start.toInt()..end.toInt()).map { bp ->
            CurvePoint(bp.toDouble(), function(bp.toDouble()), 12.0, "curve-point-$bp-v1")
        }
    }

    private fun g(bp: Double): Double = when {
        bp in 2000.0..2070.0 -> 2040.0 + 22.0 * sin((bp - 2000.0) * 2.0 * PI / 10.0) + 6.0 * sin((bp - 2000.0) * 2.0 * PI / 3.0)
        else -> bp
    }

    private inline fun <reified T> encoded(value: T): kotlinx.serialization.json.JsonObject =
        workbenchJson.encodeToJsonElement(kotlinx.serialization.serializer(), value) as kotlinx.serialization.json.JsonObject
}

@kotlinx.serialization.Serializable
data class CaseStage(val stageId: String, val title: String, val description: String)

package org.chrono.domain

import org.chrono.calib.CalibrationResult
import org.chrono.calib.SequenceAssessment

/**
 * 派生视图：当前投影下的校准结果 + 新鲜度。
 * stale 仅因「直接来源」变化（固定曲线版本指纹、测量版本、储库指纹、钉版状态）。
 * 过期结果仍可查看（storedResult 保留），但不能冒充当前结果。
 */
data class ViewEntry(
    val measurement: Measurement,
    val current: CalibrationResult?,
    val stale: Boolean,
    val staleReasons: List<String>,
    val restriction: Restriction?
)

data class ProjectView(
    val state: ProjectState,
    val entries: List<ViewEntry>,
    val assessments: List<SequenceAssessment>,
    val seq: Long
)

object Freshness {
    data class Signature(
        val measurementVersion: Int,
        val curveFingerprint: String,
        val reservoirFingerprint: String?,
        val pinnedCurveVersion: String?,
        val restrictionKey: String?
    )

    fun signatureOf(m: Measurement, result: CalibrationResult, pinned: String?, restriction: Restriction?): Signature =
        Signature(m.version, result.curveFingerprint, result.reservoirFingerprint, pinned,
            restriction?.let { "${it.fromAstro}|${it.toAstro}|${it.originEdgeId}" })
}

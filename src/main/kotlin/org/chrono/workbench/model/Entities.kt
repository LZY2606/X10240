package org.chrono.workbench.model

import kotlinx.serialization.Serializable

@Serializable
data class SourceRef(
    val kind: String,
    val name: String,
    val locator: String,
    val snippet: String = ""
)

enum class ConvertStatus { OK, UNCONVERTIBLE }

@Serializable
data class CurvePoint(
    val calBP: Int,
    val c14age: Double,
    val sigma: Double
)

@Serializable
data class Curve(
    val id: String,
    val family: String,
    val version: String,
    val displayName: String,
    val domainOldest: Int,
    val domainYoungest: Int,
    val gridStep: Int,
    val points: List<CurvePoint>,
    val source: SourceRef,
    val rawRepresentation: String,
    val generatorFingerprint: String,
    val supersedesCurveId: String? = null,
    val revisionOf: String? = null
)

@Serializable
data class Measurement(
    val id: String,
    val labCode: String,
    val ageRaw: String,
    val sigmaRaw: String,
    val unit: String,
    val material: String,
    val ageBP: Double?,
    val sigmaBP: Double?,
    val convertStatus: String,
    val diagnostics: List<String>,
    val source: SourceRef,
    val revisionOf: String? = null,
    val seq: Int = 0
)

@Serializable
enum class ReservoirKind { NORMAL, MIXTURE }

@Serializable
data class ReservoirComponent(
    val weight: Double,
    val meanYears: Double,
    val sdYears: Double
)

@Serializable
data class ReservoirOffset(
    val id: String,
    val label: String,
    val kind: ReservoirKind,
    val meanYears: Double,
    val sdYears: Double,
    val components: List<ReservoirComponent>,
    val measurementId: String,
    val source: SourceRef,
    val revisionOf: String? = null,
    val seq: Int = 0
)

@Serializable
data class EraSystem(
    val id: String,
    val name: String,
    val convention: String,
    val zeroCalBP: Int,
    val source: SourceRef
)

@Serializable
data class SequenceConstraint(
    val id: String,
    val olderMeasurementId: String,
    val youngerMeasurementId: String,
    val relation: String,
    val source: SourceRef,
    val active: Boolean = true,
    val revisionOf: String? = null,
    val seq: Int = 0
)

@Serializable
data class ExclusionDecision(
    val id: String,
    val measurementId: String,
    val decision: String,
    val reason: String,
    val decidedBy: String,
    val source: SourceRef,
    val supersedesId: String? = null,
    val seq: Int = 0
)

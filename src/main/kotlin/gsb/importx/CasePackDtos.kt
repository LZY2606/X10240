package gsb.importx

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PackSourceRef(val origin: String? = null, val detail: String? = null)

@Serializable
data class PackQuantity(val value: Double? = null, val unit: String? = null, val raw: String? = null)

@Serializable
data class PackMeasurement(
    val id: String,
    val labCode: String,
    val age14C: JsonElement? = null,
    val sigma14C: JsonElement? = null,
    val ageUnit: String? = null,
    val sampleMaterial: String? = null,
    val offsetId: String? = null,
    val source: PackSourceRef? = null
)

@Serializable
data class PackOffsetComponent(
    val mean: Double? = null,
    val sigma: Double? = null,
    val weight: Double? = null,
    val unit: String? = null
)

@Serializable
data class PackOffsetDist(val model: String? = null, val components: List<PackOffsetComponent> = emptyList())

@Serializable
data class PackOffset(
    val id: String,
    val code: String? = null,
    val name: String? = null,
    val distribution: PackOffsetDist? = null,
    val revisionOf: String? = null,
    val source: PackSourceRef? = null
)

@Serializable
data class PackCurve(val code: String? = null, val version: String? = null, val role: String? = null)

@Serializable
data class PackEra(
    val id: String? = null,
    val code: String? = null,
    val name: String? = null,
    val anchorCalBp: Double? = null,
    val note: String? = null
)

@Serializable
data class PackSequence(
    val id: String,
    val type: String? = null,
    val fromSampleId: String? = null,
    val toSampleId: String? = null,
    val windowCalBp: Double? = null,
    val source: PackSourceRef? = null
)

@Serializable
data class PackExclusion(
    val id: String,
    val targetType: String? = null,
    val targetId: String? = null,
    val regionLabel: String? = null,
    val reason: String? = null,
    val decidedBy: String? = null,
    val source: PackSourceRef? = null
)

@Serializable
data class PackChapter(
    val id: String,
    val title: String,
    val action: String,
    val offsetRevisionId: String? = null,
    val measurementId: String? = null,
    val sigma14C: Double? = null,
    val fromCurveId: String? = null,
    val toCurveVersion: String? = null
)

@Serializable
data class CasePack(
    val schemaVersion: String,
    val id: String,
    val title: String,
    val description: String = "",
    val fixedSeed: Long = 0L,
    val curves: List<PackCurve> = emptyList(),
    val eraSystem: PackEra? = null,
    val measurements: List<PackMeasurement> = emptyList(),
    val offsets: List<PackOffset> = emptyList(),
    val sequences: List<PackSequence> = emptyList(),
    val exclusions: List<PackExclusion> = emptyList(),
    val chapters: List<PackChapter> = emptyList()
)

package gsb.casepack

import gsb.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PackScenarioStep(
    val step: String,
    val sampleId: String? = null,
    val offsetName: String? = null,
    val newRaw: String? = null,
    val newMean: Double? = null,
    val newSigma: Double? = null,
    val actor: String? = null,
    val reason: String? = null,
    val constraintId: String? = null,
    val accept: String? = null,
    val otherActor: String? = null,
    val otherAccept: String? = null,
    val newCurve: String? = null,
    val level: Double? = null,
)

@Serializable
data class PackSample(
    val sampleId: String,
    val measurement: Measurement,
    val offsets: List<OffsetDist>,
    val eraSystem: String,
)

@Serializable
data class CasePack(
    val packId: String,
    val title: String,
    val curves: List<CurveDef>,
    val currentCurve: String,
    val samples: List<PackSample>,
    val constraints: List<StratConstraint> = emptyList(),
    val exclusions: List<Exclusion> = emptyList(),
    val sources: Map<String, String> = emptyMap(),
    val scenario: List<PackScenarioStep> = emptyList(),
)

object CasePacks {
    val json = Json { ignoreUnknownKeys = true }

    const val EMBEDDED_PATH = "/casepack/radiocarbon-bench.v1.json"

    fun loadEmbedded(): CasePack {
        val text = CasePacks::class.java.getResourceAsStream(EMBEDDED_PATH)?.bufferedReader()?.use { it.readText() }
            ?: error("embedded case pack missing at $EMBEDDED_PATH")
        return json.decodeFromString<CasePack>(text)
    }

    fun parse(text: String): CasePack = json.decodeFromString<CasePack>(text)
}

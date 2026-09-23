package rc.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.content

/**
 * Calendar coordinate basis. All internal math uses years BP (BP=0 at 1950 CE).
 * The era system is presentation metadata only: it must never move a
 * distribution, only format the same coordinate.
 */
@Serializable
data class EraSystem(
    val eraSystemId: String,
    val label: String,
    val epochLabel: String = "BP",
    val bpZeroGregorianYear: Int = 1950,
    val includeYearZero: Boolean = true,
    val bceLabel: String = "BCE",
    val ceLabel: String = "CE",
    val sourceRef: String
) {
    fun toJson(): JsonObject = jsonObjectOf(
        "eraSystemId" to JsonPrimitive(eraSystemId),
        "label" to JsonPrimitive(label),
        "epochLabel" to JsonPrimitive(epochLabel),
        "bpZeroGregorianYear" to JsonPrimitive(bpZeroGregorianYear),
        "includeYearZero" to JsonPrimitive(includeYearZero),
        "bceLabel" to JsonPrimitive(bceLabel),
        "ceLabel" to JsonPrimitive(ceLabel),
        "sourceRef" to JsonPrimitive(sourceRef)
    )

    companion object {
        fun fromJson(obj: JsonObject): EraSystem = EraSystem(
            eraSystemId = obj.getValue("eraSystemId").let { (it as JsonPrimitive).content },
            label = obj.getValue("label").let { (it as JsonPrimitive).content },
            epochLabel = (obj["epochLabel"] as? JsonPrimitive)?.content ?: "BP",
            bpZeroGregorianYear = (obj["bpZeroGregorianYear"] as? JsonPrimitive)?.content?.toInt() ?: 1950,
            includeYearZero = (obj["includeYearZero"] as? JsonPrimitive)?.content?.toBoolean() ?: true,
            bceLabel = (obj["bceLabel"] as? JsonPrimitive)?.content ?: "BCE",
            ceLabel = (obj["ceLabel"] as? JsonPrimitive)?.content ?: "CE",
            sourceRef = obj.getValue("sourceRef").let { (it as JsonPrimitive).content }
        )
    }
}

data class EraLabel(val text: String, val astronomicalYear: Int, val gregorianYear: Int?, val era: String)

/**
 * Format a cal BP year under the given coordinate basis.
 * calBP 0 -> 1950 CE; calBP 1950 -> the year 0 / 1 BCE boundary.
 * Astronomical year numbering (year 0 exists): astronomical = 1950 - calBP.
 * 1 CE -> 1 CE; year 0 -> boundary; -1 CE -> 1 BCE.
 */
fun formatEra(calBP: Double, system: EraSystem): EraLabel {
    val astronomical = system.bpZeroGregorianYear - Math.round(calBP).toInt()
    if (system.epochLabel == "BP") {
        return EraLabel("${Math.round(calBP)} ${system.epochLabel}", astronomical, null, "BP")
    }
    return if (system.includeYearZero) {
        when {
            astronomical >= 1 -> EraLabel("$astronomical ${system.ceLabel}", astronomical, astronomical, "CE")
            astronomical == 0 -> EraLabel("0 ${system.ceLabel}/1 ${system.bceLabel} boundary", astronomical, 0, "boundary")
            else -> {
                val bce = 1 - astronomical
                EraLabel("$bce ${system.bceLabel}", astronomical, bce, "BCE")
            }
        }
    } else {
        val gregorian = if (astronomical <= 0) astronomical - 1 else astronomical
        when {
            gregorian >= 1 -> EraLabel("$gregorian ${system.ceLabel}", astronomical, gregorian, "CE")
            else -> EraLabel("${-gregorian} ${system.bceLabel}", astronomical, -gregorian, "BCE")
        }
    }
}

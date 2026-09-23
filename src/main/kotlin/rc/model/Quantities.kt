package rc.model

import kotlinx.serialization.Serializable

/**
 * A value as originally written by the submitter. Derived layers normalise
 * [rawText] to [value]/[unit]; when conversion is impossible [value] is null
 * and a diagnostic stays attached at the same location instead of dropping the
 * representation for plotting.
 */
@Serializable
data class Quantity(
    val rawText: String,
    val value: Double? = null,
    val unit: String? = null,
    val diagnostics: List<String> = emptyList()
) {
    val isNormalized: Boolean get() = value != null && value.isFinite()
}

enum class AgeUnit(val label: String, val accepted: Set<String>) {
    BP("BP", setOf("bp", "r_yr_bp", "years bp", "14c yr bp", "radiocarbon years bp", "cal bp")),
    CAL_BP("cal BP", setOf("cal bp", "calibrated years bp", "calendar years bp")),
    DELTA_R("DeltaR", setOf("deltar", "delta-r", "r", "marine reservoir offset", "yr"));

    companion object {
        fun parse(text: String): AgeUnit? {
            val key = text.trim().lowercase()
            return entries.firstOrNull { key in it.accepted }
        }
    }
}

/** Normalise a raw age/offset token into a [Quantity], keeping failures in place. */
fun parseQuantity(rawText: String, expected: AgeUnit? = null): Quantity {
    val trimmed = rawText.trim()
    val match = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*([^0-9].*)?$""").find(trimmed)
        ?: return Quantity(trimmed, null, null, listOf("unparseable numeric value: '$trimmed'"))
    val number = match.groupValues[1].toDoubleOrNull()
    val unitText = match.groupValues.getOrNull(2)?.trim().orEmpty()
    val unit = if (unitText.isEmpty()) expected?.label else AgeUnit.parse(unitText)?.label
    val diags = mutableListOf<String>()
    if (expected != null && unitText.isNotEmpty() && AgeUnit.parse(unitText) != null && AgeUnit.parse(unitText) != expected) {
        diags += "unit '${unitText}' does not match expected ${expected.label}; value kept at original position"
    }
    if (unitText.isNotEmpty() && AgeUnit.parse(unitText) == null) {
        diags += "unknown unit '${unitText}'; retained verbatim"
    }
    if (number == null || !number.isFinite()) {
        return Quantity(trimmed, null, unit, diags + "non-finite numeric value")
    }
    if (expected == AgeUnit.BP && number < 0) {
        diags += "negative BP age is physically suspect; retained"
    }
    return Quantity(trimmed, number, unit, diags)
}

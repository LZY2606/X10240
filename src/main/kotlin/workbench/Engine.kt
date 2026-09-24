package workbench

import com.fasterxml.jackson.databind.JsonNode
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

data class ValidationResult(val errors: List<String>) {
    fun requireValid() {
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }
}

fun sha256(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { digest.update(it.toByteArray()) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun parseProjectPackage(text: String): JsonNode {
    val node = try {
        json.readTree(text)
    } catch (e: Exception) {
        throw ValidationException(listOf("package is not valid JSON: ${e.message}"))
    }
    validatePackage(node).requireValid()
    return node
}

fun validatePackage(node: JsonNode): ValidationResult {
    val errors = mutableListOf<String>()
    fun missing(path: String, message: String) = errors.add("$path: $message")
    if (!node.has("packageId")) missing("packageId", "required stable package identity")
    if (!node.has("calendarEra")) missing("calendarEra", "required calendar coordinate basis")
    if (node.path("calendarEra").asText("") !in listOf("astronomical_signed_year")) missing("calendarEra", "only astronomical_signed_year is supported")
    if (node.path("radiocarbonUnit").asText("") != "14C yr BP") missing("radiocarbonUnit", "must be 14C yr BP")
    if (!node.path("curveVersions").isArray || node.path("curveVersions").size() == 0) missing("curveVersions", "at least one versioned curve is required")
    node.path("curveVersions").forEachIndexed { ci, curve ->
        val cp = "curveVersions[$ci]"
        if (curve.path("id").asText("").isBlank()) missing("$cp.id", "required")
        if (curve.path("yearUnit").asText("") != "calendar_year_astronomical") missing("$cp.yearUnit", "must be calendar_year_astronomical")
        if (curve.path("radiocarbonUnit").asText("") != "14C yr BP") missing("$cp.radiocarbonUnit", "must be 14C yr BP")
        val points = curve.path("points")
        if (!points.isArray || points.size() < 2) missing("$cp.points", "at least two points required")
        var previous: Int? = null
        points.forEachIndexed { pi, point ->
            val pp = "$cp.points[$pi]"
            point.path("calendarYear").asText("").toIntOrNull()?.let {
                if (previous != null && it <= previous!!) missing(pp, "calendar years must increase")
                previous = it
            } ?: missing("$pp.calendarYear", "must be an integer astronomical year")
            point.path("radiocarbonAge").asText("").toDoubleOrNull() ?: missing("$pp.radiocarbonAge", "must be numeric radiocarbon years")
        }
    }
    val curveIds = node.path("curveVersions").map { it.path("id").asText() }.toSet()
    val reservoirIds = mutableSetOf<String>()
    node.path("reservoirs").forEachIndexed { ri, res ->
        val rp = "reservoirs[$ri]"
        val id = res.path("id").asText("")
        if (id.isBlank()) missing("$rp.id", "required") else reservoirIds.add(id)
        if (res.path("sourceRef").asText("").isBlank()) missing("$rp.sourceRef", "required")
        res.path("mean").asText("").toDoubleOrNull() ?: missing("$rp.mean", "must be numeric")
        res.path("sigma").asText("").toDoubleOrNull()?.let { if (it < 0) missing("$rp.sigma", "must be non-negative") }
            ?: missing("$rp.sigma", "must be numeric")
        if (res.path("unit").asText("") != "14C yr") missing("$rp.unit", "must be 14C yr")
    }
    val measurementIds = mutableSetOf<String>()
    node.path("measurements").forEachIndexed { mi, m ->
        val mp = "measurements[$mi]"
        val id = m.path("id").asText("")
        if (id.isBlank()) missing("$mp.id", "required") else if (!measurementIds.add(id)) missing("$mp.id", "duplicate measurement id")
        if (m.path("sourceRef").asText("").isBlank()) missing("$mp.sourceRef", "required")
        if (m.path("rawText").asText("").isBlank()) missing("$mp.rawText", "original representation required")
        m.path("radiocarbonAge").asText("").toDoubleOrNull() ?: missing("$mp.radiocarbonAge", "must be numeric radiocarbon years")
        m.path("sigma").asText("").toDoubleOrNull()?.let { if (it <= 0) missing("$mp.sigma", "must be positive") }
            ?: missing("$mp.sigma", "must be numeric standard deviation")
        if (m.path("unit").asText("") != "14C yr BP") missing("$mp.unit", "must be 14C yr BP")
        if (m.path("curveId").asText("") !in curveIds) missing("$mp.curveId", "must reference a bundled curve version")
        if (m.path("reservoirId").asText("") !in reservoirIds) missing("$mp.reservoirId", "must reference a reservoir distribution")
    }
    node.path("sequences").forEachIndexed { si, seq ->
        val sp = "sequences[$si]"
        if (seq.path("id").asText("").isBlank()) missing("$sp.id", "required")
        if (seq.path("olderId").asText() !in measurementIds) missing("$sp.olderId", "must reference a measurement")
        if (seq.path("youngerId").asText() !in measurementIds) missing("$sp.youngerId", "must reference a measurement")
        if (seq.path("sourceRef").asText("").isBlank()) missing("$sp.sourceRef", "required")
    }
    node.path("exclusions").forEachIndexed { ei, ex ->
        val ep = "exclusions[$ei]"
        if (ex.path("id").asText("").isBlank()) missing("$ep.id", "required")
        if (ex.path("targetId").asText() !in measurementIds) missing("$ep.targetId", "must reference a measurement")
        if (ex.path("decision").asText() != "excluded") missing("$ep.decision", "must be excluded")
        if (ex.path("sourceRef").asText("").isBlank()) missing("$ep.sourceRef", "required")
        if (ex.path("reason").asText("").isBlank()) missing("$ep.reason", "required")
    }
    return ValidationResult(errors)
}

object CalibrationEngine {
    private val hermiteNodes = doubleArrayOf(-2.020182870456086, -0.958572464613819, 0.0, 0.958572464613819, 2.020182870456086)
    private val hermiteWeights = doubleArrayOf(0.0199532420590459, 0.393619323152241, 0.945308720482942, 0.393619323152241, 0.0199532420590459)

    fun calibrate(
        measurement: Measurement,
        curve: CurveVersion,
        reservoir: Reservoir,
        domainStart: Int = curve.points.first().calendarYear,
        domainEnd: Int = curve.points.last().calendarYear,
        includeExcluded: Boolean = false
    ): Calibration? {
        if (measurement.excluded && !includeExcluded) return null
        val raw = IntArray(domainEnd - domainStart + 1) { domainStart + it }
        val curveAges = raw.map { interpolate(curve, it) }
        val totalSigma2 = measurement.sigma * measurement.sigma + reservoir.sigma * reservoir.sigma
        val likelihoods = raw.indices.map { i ->
            val curveAge = curveAges[i]
            var value = 0.0
            hermiteNodes.indices.forEach { qi ->
                val reservoirOffset = reservoir.mean + sqrt(2.0) * reservoir.sigma * hermiteNodes[qi]
                val correctedAge = measurement.age - reservoirOffset
                val z = (correctedAge - curveAge) / sqrt(totalSigma2)
                value += hermiteWeights[qi] * exp(-0.5 * z * z) / sqrt(2.0 * Math.PI * totalSigma2)
            }
            value / Math.sqrt(Math.PI)
        }
        val mass = likelihoods.sum()
        val probs = raw.indices.map {
            ProbabilityPoint(raw[it], if (mass > 0) likelihoods[it] / mass else 0.0, round6(curveAges[it]))
        }
        val interval95 = hpd(probs, 0.95, "95%")
        val interval68 = hpd(probs, 0.68, "68%")
        val intervals = interval95 + interval68
        val modes = modes(probs)
        val truncated = mass < 0.995 * domainMass(curve, measurement, reservoir)
        val id = sha256(
            "calibration", measurement.seriesId, measurement.version, curve.id,
            reservoir.seriesId, reservoir.version, ALGORITHM_VERSION, domainStart.toString(), domainEnd.toString()
        ).take(24)
        return Calibration(
            stableId = id,
            measurementSeriesId = measurement.seriesId,
            measurementVersion = measurement.version,
            curveId = curve.id,
            reservoirSeriesId = reservoir.seriesId,
            reservoirVersion = reservoir.version,
            algorithmVersion = ALGORITHM_VERSION,
            parameterFingerprint = sha256(
                measurement.rawAge, measurement.rawSigma, measurement.unit, curve.id,
                reservoir.seriesId, reservoir.version, reservoir.mean.toString(), reservoir.sigma.toString(),
                domainStart.toString(), domainEnd.toString(), ALGORITHM_VERSION
            ),
            status = if (truncated) "truncated_at_curve_boundary" else "current",
            intervals = intervals,
            probabilities = probs,
            modes = modes,
            totalMass = round6(mass),
            diagnostic = if (truncated) "normalized over the finite curve domain; tails beyond the bundled version are unknown" else null,
            integralEvidence = evidence(measurement, curve, reservoir, domainStart, domainEnd, mass),
            evidence = listOf(measurement.sourceRef, curve.id, reservoir.seriesId, reservoir.sourceRef)
        )
    }

    private fun evidence(
        measurement: Measurement,
        curve: CurveVersion,
        reservoir: Reservoir,
        domainStart: Int,
        domainEnd: Int,
        mass: Double
    ): List<JsonNode> = listOf(
        json.createObjectNode()
            .put("method", "linear_curve_interpolation")
            .put("curveId", curve.id)
            .put("interpolation", CURVE_INTERPOLATION)
            .put("startYear", domainStart)
            .put("endYear", domainEnd),
        json.createObjectNode()
            .put("method", "reservoir_quadrature")
            .put("reservoirId", reservoir.seriesId)
            .put("reservoirVersion", reservoir.version)
            .put("quadrature", RESERVOIR_QUADRATURE)
            .put("mean", reservoir.mean)
            .put("sigma", reservoir.sigma),
        json.createObjectNode()
            .put("method", "calendar_year_normalization")
            .put("rawRepresentation", measurement.rawText)
            .put("unnormalizedMass", round6(mass))
            .put("gridStep", "1 calendar_year_astronomical")
            .put("averageReported", false)
    )

    private fun domainMass(curve: CurveVersion, measurement: Measurement, reservoir: Reservoir): Double {
        var min = measurement.age - reservoir.mean - 5 * sqrt(measurement.sigma * measurement.sigma + reservoir.sigma * reservoir.sigma)
        var max = measurement.age - reservoir.mean + 5 * sqrt(measurement.sigma * measurement.sigma + reservoir.sigma * reservoir.sigma)
        min -= curve.points.minOf { it.radiocarbonAge.toDouble() }
        max -= curve.points.maxOf { it.radiocarbonAge.toDouble() }
        return 1.0
    }

    fun interpolate(curve: CurveVersion, year: Int): Double {
        val points = curve.points
        if (year <= points.first().calendarYear) return points.first().radiocarbonAge
        if (year >= points.last().calendarYear) return points.last().radiocarbonAge
        val index = points.indexOfLast { it.calendarYear <= year }
        val left = points[index]
        val right = points[index + 1]
        val fraction = (year - left.calendarYear).toDouble() / (right.calendarYear - left.calendarYear)
        return left.radiocarbonAge + fraction * (right.radiocarbonAge - left.radiocarbonAge)
    }

    private fun hpd(points: List<ProbabilityPoint>, target: Double, level: String): List<Interval> {
        var threshold = points.maxOf { it.p }
        var selected: List<ProbabilityPoint> = emptyList()
        var guard = 0
        while (selected.sumOf { it.p } < target && threshold > 0 && guard < 1000) {
            selected = points.filter { it.p >= threshold }
            threshold *= 0.985
            guard++
        }
        if (selected.isEmpty()) selected = listOf(points.maxBy { it.p })
        val groups = mutableListOf<MutableList<ProbabilityPoint>>()
        selected.sortedBy { it.calendarYear }.forEach { point ->
            val group = groups.lastOrNull()
            if (group != null && point.calendarYear == group.last().calendarYear + 1) group.add(point) else groups.add(mutableListOf(point))
        }
        return groups.mapIndexed { index, group ->
            val start = group.first().calendarYear
            val end = group.last().calendarYear
            Interval(
                stableId = sha256("interval", level, start.toString(), end.toString(), points.joinToString(",") { "${it.calendarYear}:${it.p}" }).take(20),
                level = level,
                startYear = start,
                endYear = end,
                label = "${eraLabel(start)}–${eraLabel(end)}",
                probability = round6(group.sumOf { it.p }),
                years = (start..end).toList(),
                integralBasis = "sum of normalized 1-calendar-year probabilities at fixed $ALGORITHM_VERSION"
            )
        }
    }

    private fun modes(points: List<ProbabilityPoint>): List<Int> {
        val result = mutableListOf<Int>()
        points.indices.forEach { i ->
            val previous = points.getOrNull(i - 1)?.p ?: 0.0
            val next = points.getOrNull(i + 1)?.p ?: 0.0
            if (points[i].p > previous && points[i].p >= next && points[i].p > 0.0005) result.add(points[i].calendarYear)
        }
        return result
    }

    private fun eraLabel(year: Int): String = if (year < 0) "${-year} BCE" else if (year == 0) "0 BCE/CE" else "$year CE"
    private fun round6(value: Double): Double {
        val factor = 1_000_000.0
        return Math.round(value * factor).toDouble() / factor
    }
}

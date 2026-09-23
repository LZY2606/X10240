package workbench

import kotlin.math.*

// ---------------------------------------------------------------------------
// Quantities, units and coordinate bases.
// Raw representations are preserved at import; normalization happens only in
// the derived layer. Values that cannot be converted stay in place with a
// diagnostic attached (see Service.kt).
// ---------------------------------------------------------------------------

object Units {
    const val BP14C = "BP14C"        // radiocarbon years before 1950
    const val CAL_BP = "calBP"       // calendar years before 1950
    const val CE = "CE"              // calendar year, Common Era (no year 0)
    const val BCE = "BCE"            // calendar year, Before Common Era (no year 0)
    val KNOWN = setOf(BP14C, CAL_BP, CE, BCE)
}

/** A measured quantity keeping its original textual representation. */
data class Quantity(val raw: String, val unit: String) {
    fun numeric(): Double? = raw.trim().toDoubleOrNull()
    fun convertible(): Boolean = unit in Units.KNOWN && numeric() != null
}

enum class Era { BCE, CE }

data class EraYear(val year: Int, val era: Era) {
    init { require(year >= 1) { "no year 0 in BCE/CE system" } }
    override fun toString(): String = "$year $era"
}

/** BCE/CE era system without a year 0. calBP 0 == 1950 CE. */
object EraSystem {
    const val ID = "bce-ce-noyearzero"

    fun calBpToEra(calBp: Double): EraYear {
        val ce = 1950.0 - calBp
        return if (ce >= 1.0) EraYear(max(1, ce.roundToInt()), Era.CE)
        else EraYear(max(1, (1.0 - ce).roundToInt()), Era.BCE)
    }

    fun eraToCalBp(year: Int, era: Era): Double =
        if (era == Era.CE) 1950.0 - year else 1949.0 + year

    fun label(calBp: Double): String = "${calBp.roundToInt()} calBP (${calBpToEra(calBp)})"
}

// ---------------------------------------------------------------------------
// Calibration curve: versioned, linear interpolation, boundary clamping.
// ---------------------------------------------------------------------------

data class CurvePoint(val calBp: Double, val c14Bp: Double, val sigma: Double)

class CalibrationCurve(points: List<CurvePoint>) {
    val points: List<CurvePoint> = points.sortedBy { it.calBp }
    val minCalBp: Double get() = points.first().calBp
    val maxCalBp: Double get() = points.last().calBp

    /** Linear interpolation. Outside the curve range the nearest endpoint is
     *  returned and [InterpResult.boundaryClamped] is true. */
    fun interpolate(calBp: Double): InterpResult {
        if (calBp <= minCalBp) return InterpResult(points.first(), boundaryClamped = calBp < minCalBp)
        if (calBp >= maxCalBp) return InterpResult(points.last(), boundaryClamped = calBp > maxCalBp)
        val idx = points.indexOfFirst { it.calBp >= calBp }
        val hi = points[idx]
        val lo = points[idx - 1]
        val t = (calBp - lo.calBp) / (hi.calBp - lo.calBp)
        return InterpResult(
            CurvePoint(
                calBp = calBp,
                c14Bp = lo.c14Bp + t * (hi.c14Bp - lo.c14Bp),
                sigma = lo.sigma + t * (hi.sigma - lo.sigma)
            ),
            boundaryClamped = false
        )
    }
}

data class InterpResult(val point: CurvePoint, val boundaryClamped: Boolean)

// ---------------------------------------------------------------------------
// Calibration: measured ^14C age + reservoir offset distribution + curve ->
// normalized calendar probability density. The multi-modal distribution is
// kept in full; it is never collapsed to a single mean year.
// ---------------------------------------------------------------------------

const val ALGO_CALIBRATION = "calib-1.0"
const val ALGO_INTERVALS = "hpd-1.0"
const val ALGO_STRAT = "strat-1.0"

data class DensityCell(val calBp: Double, val density: Double)

data class IntegrationEvidence(
    val cellCount: Int,
    val rawSum: Double,
    val norm: Double,
    val step: Double,
    val probability: Double
)

data class CalendarInterval(
    val fromCalBp: Double,   // younger edge (smaller calBP)
    val toCalBp: Double,     // older edge (larger calBP)
    val level: Double,       // 0.6827 / 0.9545
    val probability: Double,
    val evidence: IntegrationEvidence
) {
    fun fromEra(): EraYear = EraSystem.calBpToEra(fromCalBp)
    fun toEra(): EraYear = EraSystem.calBpToEra(toCalBp)
    fun crossesEraBoundary(): Boolean = fromEra().era != toEra().era
}

data class CalibrationOutput(
    val cells: List<DensityCell>,
    val step: Double,
    val norm: Double,
    val intervals: List<CalendarInterval>,
    val modes: List<Double>,
    val boundaryClamped: Boolean,
    val diagnostics: List<String>
) {
    fun totalMass(): Double = cells.sumOf { it.density } * step
}

object Calibrator {

    fun calibrate(
        measuredC14: Double,
        measuredSigma: Double,
        offsetMean: Double,
        offsetSigma: Double,
        curve: CalibrationCurve,
        step: Double = 1.0
    ): CalibrationOutput {
        val diagnostics = mutableListOf<String>()
        val effMean = measuredC14 - offsetMean
        val effVar = measuredSigma * measuredSigma + offsetSigma * offsetSigma

        var clamped = false
        val raw = ArrayList<DensityCell>()
        var calBp = curve.minCalBp
        while (calBp <= curve.maxCalBp + 1e-9) {
            val interp = curve.interpolate(calBp)
            if (interp.boundaryClamped) clamped = true
            val s = sqrt(effVar + interp.point.sigma * interp.point.sigma)
            val z = (interp.point.c14Bp - effMean) / s
            raw.add(DensityCell(calBp, exp(-0.5 * z * z) / s))
            calBp += step
        }
        if (clamped) diagnostics.add(
            "boundary-clamp: calibration window reached curve edge " +
                "[${curve.minCalBp}, ${curve.maxCalBp}] calBP; endpoint values used"
        )

        val norm = raw.sumOf { it.density } * step
        require(norm > 0.0) { "degenerate calibration: zero total probability" }
        val cells = raw.map { DensityCell(it.calBp, it.density / norm) }

        val intervals = hpdIntervals(cells, step, 0.6827) + hpdIntervals(cells, step, 0.9545)
        return CalibrationOutput(
            cells = cells,
            step = step,
            norm = norm,
            intervals = intervals.sortedWith(compareBy({ it.level }, { it.fromCalBp })),
            modes = findModes(cells),
            boundaryClamped = clamped,
            diagnostics = diagnostics
        )
    }

    /** Highest-density regions: sort cells by density, accumulate to [level],
     *  then group contiguous runs. Parallel calendar intervals are preserved. */
    fun hpdIntervals(cells: List<DensityCell>, step: Double, level: Double): List<CalendarInterval> {
        val order = cells.indices.sortedByDescending { cells[it].density }
        var acc = 0.0
        var k = 0
        while (k < order.size && acc < level) {
            acc += cells[order[k]].density * step
            k++
        }
        if (k == 0) return emptyList()
        val threshold = cells[order[k - 1]].density

        val intervals = mutableListOf<CalendarInterval>()
        var i = 0
        while (i < cells.size) {
            if (cells[i].density >= threshold) {
                var j = i
                var rawSum = 0.0
                while (j < cells.size && cells[j].density >= threshold) {
                    rawSum += cells[j].density
                    j++
                }
                val prob = rawSum * step
                intervals.add(
                    CalendarInterval(
                        fromCalBp = cells[i].calBp,
                        toCalBp = cells[j - 1].calBp,
                        level = level,
                        probability = prob,
                        evidence = IntegrationEvidence(
                            cellCount = j - i,
                            rawSum = rawSum,
                            norm = 1.0, // cells already normalized; norm recorded on result payload
                            step = step,
                            probability = prob
                        )
                    )
                )
                i = j
            } else i++
        }
        return intervals
    }

    /** Local maxima of the density (multi-modality is first-class). */
    fun findModes(cells: List<DensityCell>): List<Double> {
        val modes = mutableListOf<Double>()
        for (i in 1 until cells.size - 1) {
            if (cells[i].density > cells[i - 1].density && cells[i].density >= cells[i + 1].density) {
                if (modes.isEmpty() || cells[i].calBp - modes.last() > 1e-9) modes.add(cells[i].calBp)
            }
        }
        if (modes.isEmpty() && cells.isNotEmpty()) {
            modes.add(cells.maxBy { it.density }.calBp)
        }
        return modes
    }
}

// ---------------------------------------------------------------------------
// Stratigraphy: ordering constraints between samples. Conflicts produce
// candidate adjustments; measurements themselves are never rewritten.
// ---------------------------------------------------------------------------

data class StratCandidate(
    val mode: String,               // truncate-older | truncate-younger | mutual
    val note: String,
    val olderCells: List<DensityCell>,
    val youngerCells: List<DensityCell>
)

data class StratCheck(
    val probOrderHolds: Double,
    val conflict: Boolean,
    val candidates: List<StratCandidate>,
    val diagnostics: List<String>
)

object Stratigraphy {
    const val CONFLICT_THRESHOLD = 0.5

    /** P(older sample is truly older) = P(calBpA > calBpB) under the two
     *  independent calibrated densities. */
    fun probOrderHolds(older: CalibrationOutput, younger: CalibrationOutput): Double {
        // suffix mass of younger distribution
        val yc = younger.cells
        val suffix = DoubleArray(yc.size + 1)
        for (i in yc.indices.reversed()) suffix[i] = suffix[i + 1] + yc[i].density * younger.step
        var p = 0.0
        for (a in older.cells) {
            // mass of younger cells with calBp < a.calBp
            val idx = yc.indexOfFirst { it.calBp >= a.calBp }
            val massBelow = if (idx < 0) 0.0 else 1.0 - suffix[idx]
            p += a.density * older.step * massBelow
        }
        return p
    }

    fun check(older: CalibrationOutput, younger: CalibrationOutput): StratCheck {
        val p = probOrderHolds(older, younger)
        val conflict = p < CONFLICT_THRESHOLD
        val diagnostics = mutableListOf<String>()
        val candidates = mutableListOf<StratCandidate>()
        if (conflict) {
            diagnostics.add(
                "stratigraphic conflict: P(order holds)=%.4f < %.2f".format(p, CONFLICT_THRESHOLD)
            )
            val olderMedian = median(older)
            val youngerMedian = median(younger)
            addCandidate(candidates, diagnostics, "truncate-older",
                "keep older-sample cells at or above younger median (${youngerMedian.roundToInt()} calBP)",
                older.cells.filter { it.calBp >= youngerMedian }, younger.cells, older.step, younger.step)
            addCandidate(candidates, diagnostics, "truncate-younger",
                "keep younger-sample cells at or below older median (${olderMedian.roundToInt()} calBP)",
                older.cells, younger.cells.filter { it.calBp <= olderMedian }, older.step, younger.step)
            val cut = (olderMedian + youngerMedian) / 2.0
            addCandidate(candidates, diagnostics, "mutual",
                "both truncated at midpoint ${cut.roundToInt()} calBP",
                older.cells.filter { it.calBp >= cut }, younger.cells.filter { it.calBp <= cut },
                older.step, younger.step)
        }
        return StratCheck(p, conflict, candidates, diagnostics)
    }

    private fun addCandidate(
        out: MutableList<StratCandidate>,
        diagnostics: MutableList<String>,
        mode: String,
        note: String,
        olderRaw: List<DensityCell>,
        youngerRaw: List<DensityCell>,
        olderStep: Double,
        youngerStep: Double
    ) {
        if (olderRaw.isEmpty() || youngerRaw.isEmpty()) {
            diagnostics.add("candidate '$mode' discarded: truncation leaves an empty distribution")
            return
        }
        out.add(StratCandidate(mode, note, renormalize(olderRaw, olderStep), renormalize(youngerRaw, youngerStep)))
    }

    fun renormalize(cells: List<DensityCell>, step: Double): List<DensityCell> {
        val norm = cells.sumOf { it.density } * step
        return cells.map { DensityCell(it.calBp, it.density / norm) }
    }

    fun median(output: CalibrationOutput): Double {
        var acc = 0.0
        for (c in output.cells) {
            acc += c.density * output.step
            if (acc >= 0.5) return c.calBp
        }
        return output.cells.last().calBp
    }
}

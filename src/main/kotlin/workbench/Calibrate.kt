package workbench

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.sqrt

/** Display basis for calendar values. */
enum class EraBasis { CAL_BP, BCE_CE }

/** Convert a calBP value to a civil BCE/CE label. There is no year zero:
 *  astronomical year y = 1950 - calBP; y >= 1 -> "y CE"; y <= 0 -> "${1-y} BCE". */
fun calBPToEraLabel(calBP: Double): String {
    val astro = 1950.0 - calBP
    val year = astro.toInt()
    return if (astro >= 1.0) "$year CE" else "${1 - year} BCE"
}

fun formatCal(calBP: Double, basis: EraBasis): String = when (basis) {
    EraBasis.CAL_BP -> "${calBP.toInt()} calBP"
    EraBasis.BCE_CE -> calBPToEraLabel(calBP)
}

@Serializable
data class CalibrationInput(
    val measuredC14BP: Double,
    val measuredSigmaBP: Double,
    val deltaR: Double = 0.0,
    val deltaRSigma: Double = 0.0,
    val gridStepCalBP: Double = 1.0,
)

/** One highest-posterior-density interval with integration evidence. */
@Serializable
data class HpdInterval(
    val level: Double,
    val fromCalBP: Double,
    val toCalBP: Double,
    val mass: Double,
    val fromEra: String,
    val toEra: String,
    /** Integration evidence, expandable in the UI. */
    val gridStepCalBP: Double,
    val gridPoints: Int,
    val rawMassBeforeNormalization: Double,
    val normalizationConstant: Double,
)

@Serializable
data class CalibrationOutput(
    val algorithm: String,
    val input: CalibrationInput,
    val curveId: String,
    val curveVersion: Int,
    val gridStartCalBP: Double,
    val gridStepCalBP: Double,
    /** Posterior density per grid point, normalized so sum(density)*step == 1. Never collapsed to a mean. */
    val density: List<Double>,
    val intervals: List<HpdInterval>,
    val diagnostics: List<String>,
) {
    val gridEndCalBP: Double get() = gridStartCalBP + gridStepCalBP * (density.size - 1)
}

object Calibrate {
    const val ALGORITHM = "cal-engine/1.0"
    val LEVELS = listOf(0.954, 0.682)

    /**
     * Classical radiocarbon calibration. For each calendar grid point t:
     *   mu  = curve.c14(t) + deltaR
     *   var = curve.sigma(t)^2 + measuredSigma^2 + deltaRSigma^2
     *   p(t) ∝ exp(-(mu - m)^2 / (2 var)) / sqrt(var)
     * The multi-modal posterior is kept as a full density grid; HPD interval
     * sets are derived from it. No point estimate is produced.
     */
    fun calibrate(curve: CalibrationCurve, input: CalibrationInput): CalibrationOutput {
        val diagnostics = mutableListOf<String>()
        val step = input.gridStepCalBP
        val n = ((curve.maxCalBP - curve.minCalBP) / step).toInt() + 1
        val raw = DoubleArray(n)
        var clippedLow = false
        var clippedHigh = false
        val m = input.measuredC14BP
        for (i in 0 until n) {
            val t = curve.minCalBP + i * step
            val mu = curve.c14At(t) + input.deltaR
            val varTotal = curve.sigmaAt(t) * curve.sigmaAt(t) +
                input.measuredSigmaBP * input.measuredSigmaBP +
                input.deltaRSigma * input.deltaRSigma
            val z = (mu - m)
            raw[i] = exp(-0.5 * z * z / varTotal) / sqrt(varTotal)
        }
        // Boundary diagnostics: does the posterior mass run into a table edge?
        val total = raw.sum()
        if (total > 0) {
            val edgeMassLow = raw.take(3).sum() / total
            val edgeMassHigh = raw.takeLast(3).sum() / total
            if (edgeMassLow > 0.001) clippedLow = true
            if (edgeMassHigh > 0.001) clippedHigh = true
        }
        if (clippedLow) diagnostics += "boundary: posterior mass reaches lower curve table edge (${curve.minCalBP} calBP); density outside table is zero"
        if (clippedHigh) diagnostics += "boundary: posterior mass reaches upper curve table edge (${curve.maxCalBP} calBP); density outside table is zero"
        if (input.deltaRSigma > 0 || input.deltaR != 0.0)
            diagnostics += "reservoir-offset: deltaR=${input.deltaR}±${input.deltaRSigma} applied as distribution, widening likelihood"

        val norm = total * step
        val density = raw.map { it / norm }
        val intervals = LEVELS.flatMap { level -> hpd(density, step, curve.minCalBP, level, norm) }
            .sortedWith(compareBy({ it.level }, { it.fromCalBP }))
        return CalibrationOutput(
            algorithm = ALGORITHM,
            input = input,
            curveId = curve.curveId,
            curveVersion = curve.version,
            gridStartCalBP = curve.minCalBP,
            gridStepCalBP = step,
            density = density,
            intervals = intervals,
            diagnostics = diagnostics,
        )
    }

    /** HPD interval set: accumulate grid points by descending density until [level] mass is reached. */
    private fun hpd(density: List<Double>, step: Double, start: Double, level: Double, norm: Double): List<HpdInterval> {
        val order = density.indices.sortedByDescending { density[it] }
        val selected = BooleanArray(density.size)
        var acc = 0.0
        for (i in order) {
            if (acc >= level) break
            selected[i] = true
            acc += density[i] * step
        }
        val intervals = mutableListOf<HpdInterval>()
        var i = 0
        while (i < density.size) {
            if (!selected[i]) { i++; continue }
            var j = i
            var mass = 0.0
            while (j < density.size && selected[j]) { mass += density[j] * step; j++ }
            val from = start + i * step
            val to = start + (j - 1) * step
            intervals += HpdInterval(
                level = level,
                fromCalBP = from,
                toCalBP = to,
                mass = mass,
                fromEra = calBPToEraLabel(from),
                toEra = calBPToEraLabel(to),
                gridStepCalBP = step,
                gridPoints = j - i,
                rawMassBeforeNormalization = mass * norm,
                normalizationConstant = norm,
            )
            i = j
        }
        return intervals
    }
}

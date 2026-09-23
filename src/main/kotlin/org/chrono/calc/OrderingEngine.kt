package org.chrono.calc

import kotlinx.serialization.Serializable
import org.chrono.model.IdKinds
import org.chrono.model.SequenceConstraintSpec
import org.chrono.model.Years
import kotlin.math.roundToInt

@Serializable
data class ConditionedSample(
    val sampleCode: String,
    val cells: List<DensityCell>,
    val intervals: List<HpdInterval>,
)

@Serializable
data class EdgeReport(
    val olderSample: String,
    val youngerSample: String,
    val forwardProbabilityAlpha: Double,
)

@Serializable
data class CandidateAdjustment(
    val candidateId: String,
    val type: String,
    val subject: String,
    val description: String,
    val preservesMeasurement: Boolean,
)

@Serializable
data class OrderingResult(
    val resultId: String,
    val chainCode: String,
    val algorithmVersion: String,
    val samplesOldestFirst: List<String>,
    val orderProbability: Double,
    val state: String,
    val conflictCode: String?,
    val edges: List<EdgeReport>,
    val conditioned: List<ConditionedSample>,
    val candidateAdjustments: List<CandidateAdjustment>,
    val unresolvableLegalCandidates: List<CandidateAdjustment>,
)

/**
 * Sequential (chain) chronological ordering by one-pass forward / backward belief
 * propagation on a shared calendar grid, following the standard Bayesian calibration
 * ordering method. Measurements are never modified by this engine: conflicts surface
 * candidate adjustments only.
 */
class OrderingEngine(private val config: CalibrationConfig = CalibrationConfig()) {

    fun evaluate(
        chain: SequenceConstraintSpec,
        chainCode: String,
        calibrationsOldestFirst: List<CalibrationResult>,
        exclusions: Set<String>,
    ): OrderingResult {
        val active = calibrationsOldestFirst.filterNot { it.sampleCode in exclusions }
        require(active.size >= 2) { "chain $chainCode needs at least two non-excluded members" }
        validateChain(chain, active.map { it.sampleCode }.toSet())

        val n = active.size
        val ref = active.first()
        active.forEach { result ->
            require(result.cells.size == ref.cells.size && result.stepYear == ref.stepYear) {
                "chain $chainCode mixes grids; recalibrate samples on one curve/grid"
            }
        }
        val grid = ref.cells
        val m = grid.size
        val step = ref.stepYear
        val marginals = active.map { result -> DoubleArray(m) { result.cells[it].densityPerYear } }

        // Forward pass: alpha_j = integral phi_j, phi_1 = marginal_1,
        // phi_j(x)=marg_j(x) * integral_{y>x} phi_{j-1}(y) dy.
        val phi = Array(n) { DoubleArray(m) }
        val alpha = DoubleArray(n)
        System.arraycopy(marginals[0], 0, phi[0], 0, m)
        alpha[0] = phi[0].sumTimesStep(step)
        for (j in 1 until n) {
            val suffix = suffixSums(phi[j - 1], step)
            var mass = 0.0
            for (i in 0 until m) {
                phi[j][i] = marginals[j][i] * suffix[i]
                mass += phi[j][i] * step
            }
            alpha[j] = mass
            if (mass > 0.0) for (i in 0 until m) phi[j][i] /= mass
        }
        val orderProbability = alpha.drop(1).fold(1.0) { acc, value -> acc * value }

        // Backward pass: conditional marginal q_j(x)=marg_j(x)*backward_j(x)*forward_j(x).
        val backward = Array(n) { DoubleArray(m) { 1.0 } }
        for (j in n - 2 downTo 0) {
            val prior = DoubleArray(m) { marginals[j + 1][it] * backward[j + 1][it] }
            val prefix = prefixSums(prior, step)
            for (i in 0 until m) backward[j][i] = prefix[i]
        }
        val conditioned = ArrayList<ConditionedSample>(n)
        for (j in 0 until n) {
            val raw = DoubleArray(m) { marginals[j][it] * backward[j][it] * (if (j == 0) 1.0 else suffixSums(phi[j - 1], step)[it]) }
            val norm = raw.sumTimesStep(step)
            val cells = if (norm > 0.0) {
                grid.mapIndexed { index, cell -> cell.copy(densityPerYear = raw[index] / norm) }
            } else grid
            conditioned += ConditionedSample(
                sampleCode = active[j].sampleCode,
                cells = cells,
                intervals = intervalsFor(active[j].sampleCode, cells, ref),
            )
        }

        val conflict = orderProbability < CONFLICT_THRESHOLD
        val edges = chain.edges
            .filter { edge -> active.any { it.sampleCode == edge.olderSample } && active.any { it.sampleCode == edge.youngerSample } }
            .mapIndexed { index, edge ->
                EdgeReport(edge.olderSample, edge.youngerSample, alpha.getOrElse(index + 1) { 0.0 })
            }

        val candidates = if (conflict) buildCandidates(chain, active, exclusions) else emptyList()

        return OrderingResult(
            resultId = "${IdKinds.ORDERING}_$chainCode",
            chainCode = chainCode,
            algorithmVersion = Algorithms.ORDERING,
            samplesOldestFirst = active.map { it.sampleCode },
            orderProbability = orderProbability,
            state = if (conflict) "conflict" else if (orderProbability < 0.5) "weak" else "consistent",
            conflictCode = if (conflict) "SEQ-CONFLICT-PLT-05" else null,
            edges = edges,
            conditioned = conditioned,
            candidateAdjustments = candidates,
            unresolvableLegalCandidates = if (conflict) candidates else emptyList(),
        )
    }

    private fun validateChain(chain: SequenceConstraintSpec, members: Set<String>) {
        val older = chain.edges.map { it.olderSample }.toSet()
        val younger = chain.edges.map { it.youngerSample }.toSet()
        require(chain.edges.isNotEmpty()) { "chain ${chain.chainCode} has no edges" }
        require(older.size == chain.edges.size) { "chain ${chain.chainCode} must be a strict single chain (branching older)" }
        require(younger.size == chain.edges.size) { "chain ${chain.chainCode} must be a strict single chain (branching younger)" }
        val referenced = older + younger
        val unknown = referenced - members
        require(unknown.isEmpty()) {
            "chain ${chain.chainCode} references samples absent or excluded: ${unknown.sorted().joinToString()}"
        }
    }

    private fun buildCandidates(
        chain: SequenceConstraintSpec,
        active: List<CalibrationResult>,
        exclusions: Set<String>,
    ): List<CandidateAdjustment> {
        val list = mutableListOf<CandidateAdjustment>()
        var k = 0
        chain.edges.forEach { edge ->
            list += CandidateAdjustment(
                candidateId = "${IdKinds.CANDIDATE}_${chain.chainCode}_offset_${k}",
                type = "review-reservoir-offset",
                subject = edge.youngerSample,
                description = "Review the reservoir offset hypothesis on ${edge.youngerSample}: a smaller delta-R shifts its calibrated date older and may resolve the order with ${edge.olderSample}. Measurement is unchanged.",
                preservesMeasurement = true,
            )
            list += CandidateAdjustment(
                candidateId = "${IdKinds.CANDIDATE}_${chain.chainCode}_edge_${k}",
                type = "relax-sequence-edge",
                subject = "${edge.olderSample}>${edge.youngerSample}",
                description = "Relax or re-evaluate the stratigraphic edge ${edge.olderSample} older-than ${edge.youngerSample}. Measurement is unchanged.",
                preservesMeasurement = true,
            )
            k++
        }
        active.forEach { result ->
            list += CandidateAdjustment(
                candidateId = "${IdKinds.CANDIDATE}_${chain.chainCode}_exclude_${result.sampleCode}",
                type = "exclude-sample-from-chain",
                subject = result.sampleCode,
                description = "Exclude ${result.sampleCode} from chain ${chain.chainCode} pending re-examination. The radiocarbon measurement is retained and source-referenced.",
                preservesMeasurement = true,
            )
        }
        return list
    }

    private fun intervalsFor(sampleCode: String, cells: List<DensityCell>, ref: CalibrationResult): List<HpdInterval> {
        val step = ref.stepYear
        val sorted = cells.sortedByDescending { it.densityPerYear }
        val out = mutableListOf<HpdInterval>()
        listOf(0.68, 0.95).forEachIndexed { levelIndex, level ->
            val chosen = HashSet<Int>()
            var acc = 0.0
            for (cell in sorted) {
                chosen += cell.index
                acc += cell.densityPerYear * step
                if (acc >= level - 1e-12) break
            }
            var segment = mutableListOf<DensityCell>()
            var segmentIndex = 0
            for (cell in cells) {
                if (cell.index in chosen) segment += cell else if (segment.isNotEmpty()) {
                    out += hpd(sampleCode, level, levelIndex, segmentIndex, segment, step)
                    segmentIndex++
                    segment = mutableListOf()
                }
            }
            if (segment.isNotEmpty()) {
                out += hpd(sampleCode, level, levelIndex, segmentIndex, segment, step)
            }
        }
        return out
    }

    private fun hpd(
        sampleCode: String,
        level: Double,
        levelIndex: Int,
        segmentIndex: Int,
        segment: List<DensityCell>,
        step: Double,
    ): HpdInterval = HpdInterval(
        intervalId = "${IdKinds.INTERVAL}_${sampleCode}_seq_${(level * 100).roundToInt()}_$segmentIndex",
        level = level,
        astroStart = segment.first().astroMid - step / 2,
        astroEnd = segment.last().astroMid + step / 2,
        calBpStart = segment.last().calBpMid + step / 2,
        calBpEnd = segment.first().calBpMid - step / 2,
        labelStart = Years.bceCeLabel(segment.first().astroMid - step / 2),
        labelEnd = Years.bceCeLabel(segment.last().astroMid + step / 2),
        probability = segment.sumOf { it.densityPerYear } * step,
        crossesBceCe = Years.crossesBceCe(segment.first().astroMid - step / 2, segment.last().astroMid + step / 2),
        truncatedAtCurveBoundary = segment.any { !it.curveInside },
    )

    private fun DoubleArray.sumTimesStep(step: Double): Double {
        var total = 0.0
        for (value in this) total += value * step
        return total
    }

    private fun suffixSums(values: DoubleArray, step: Double): DoubleArray {
        val out = DoubleArray(values.size)
        var acc = 0.0
        for (i in values.lastIndex downTo 0) {
            acc += values[i] * step
            out[i] = acc
        }
        return out
    }

    private fun prefixSums(values: DoubleArray, step: Double): DoubleArray {
        val out = DoubleArray(values.size)
        var acc = 0.0
        for (i in values.indices) {
            out[i] = acc
            acc += values[i] * step
        }
        return out
    }

    companion object {
        const val CONFLICT_THRESHOLD = 0.05
    }
}

package org.chrono.cases

import org.chrono.import_.CasePackage
import org.chrono.model.CalibrationCurveSpec
import org.chrono.model.CurvePoint
import org.chrono.model.ExclusionSpec
import org.chrono.model.EpochSystemSpec
import org.chrono.model.MeasurementSpec
import org.chrono.model.OffsetBindingSpec
import org.chrono.model.OffsetDist
import org.chrono.model.Qty
import org.chrono.model.ReservoirSpec
import org.chrono.model.SequenceConstraintSpec
import org.chrono.model.SequenceEdge
import org.chrono.model.Units
import kotlin.math.PI
import kotlin.math.sin

/**
 * Authoritative builder for the offline case pack
 * "carbon-seat: calibration curves, reservoir offsets and multi-modal calendar evidence".
 *
 * Curves are synthetic but deliberately shaped like real wiggle sections: plateaus and sharp
 * swings create multiple equally-legal calendar intervals. Two frozen versions (DemoCal-1.0
 * and DemoCal-2.0) let reviewers exercise a curve upgrade while preserving the old one.
 * Grid span is cal BP 2100..1660 (astronomical years -150..290), so the BCE/CE boundary is
 * covered explicitly.
 */
object CaseFactory {
    const val PACKAGE_ID = "carbon-seat-offline-v1"
    const val PROJECT_CODE = "CS-OFFLINE-001"

    private val base = 1880.0
    private val phase = 0.35

    private fun wiggle1(x: Double): Double =
        95.0 * sin(2.0 * PI * x / 120.0 + phase) +
            35.0 * sin(2.0 * PI * x / 52.0 + 1.1)

    private fun wiggle2(x: Double): Double =
        70.0 * sin(2.0 * PI * x / 132.0 + phase + 0.25) +
            52.0 * sin(2.0 * PI * x / 50.0 + 1.9)

    private fun curve(version: String, wiggle: (Double) -> Double, note: String): CalibrationCurveSpec {
        val points = (0..88).map { index ->
            val calBp = 2100.0 - index * 5.0
            val astro = 1950.0 - calBp
            CurvePoint(
                calBp = calBp,
                c14Bp = base - astro + wiggle(astro),
                sigma = 22.0 + (index % 7) * 0.7,
            )
        }
        return CalibrationCurveSpec(
            curveId = "DemoCal",
            version = version,
            points = points,
            sourceRef = "offline-case:$PACKAGE_ID/curves/DemoCal@$version",
            publishedNote = note,
        )
    }

    fun v1(): CalibrationCurveSpec = curve("1.0", { x -> wiggle1(x) },
        "Synthetic wiggle curve frozen for the offline case (initial baseline).")

    fun v2(): CalibrationCurveSpec = curve("2.0", { x -> wiggle2(x) },
        "Synthetic wiggle curve, revised wiggles: exercises a curve upgrade; v1 remains immutable.")

    private fun measurement(
        code: String,
        age: Double,
        sigma: Double,
        rawAge: String,
        rawSigma: String,
        material: String,
        lab: String,
        ref: String,
    ) = MeasurementSpec(
        sampleCode = code,
        age = Qty(age, Units.C14_YR_BP, Units.DATUM_BP1950),
        sigma = Qty(sigma, Units.C14_YR_BP, Units.DATUM_BP1950),
        rawAgeText = rawAge,
        rawSigmaText = rawSigma,
        materialNote = material,
        laboratoryRef = lab,
        sourceRef = ref,
    )

    fun baselinePackage(): CasePackage {
        val curve1 = v1()
        return CasePackage(
            packageId = PACKAGE_ID,
            title = "碳年候选席：校准曲线、储库偏移与多峰年代证据",
            projectCode = PROJECT_CODE,
            narrative = "Initial baseline: every measurement, curve version, offset distribution, epoch datum, sequence edge and exclusion is independently sourced.",
            curves = listOf(curve1),
            measurements = listOf(
                measurement("CS-A", 1960.0, 30.0, "1960 ± 30 BP", "30", "charcoal, short-lived twig", "Lab-ALPHA/CS-A", "lab-sheet:CS-A row 12"),
                measurement("CS-B", 2150.0, 38.0, "2150±38 BP", "38", "marine shell (Mytilus)", "Lab-ALPHA/CS-B", "lab-sheet:CS-B row 13"),
                measurement("CS-C", 1760.0, 26.0, "1760 ±26", "±26", "terrestrial peat macrofossil", "Lab-ALPHA/CS-C", "lab-sheet:CS-C row 14"),
                measurement("CS-D", 1950.0, 30.0, "1950+/-30 BP", "30", "charred grain, BCE/CE boundary context", "Lab-ALPHA/CS-D", "lab-sheet:CS-D row 15"),
                measurement("CS-E", 1420.0, 25.0, "1420 ± 25 BP", "25", "bulk sediment; suspected modern root input", "Lab-ALPHA/CS-E", "lab-sheet:CS-E row 16"),
                measurement("CS-F", 1820.0, 28.0, "1820±28 BP", "28", "bone collagen, lower stratum", "Lab-ALPHA/CS-F", "lab-sheet:CS-F row 17"),
                measurement("CS-G", 1900.0, 28.0, "1900±28 BP", "28", "bone collagen, upper stratum", "Lab-ALPHA/CS-G", "lab-sheet:CS-G row 18"),
            ),
            reservoirs = listOf(
                ReservoirSpec(
                    reservoirCode = "R-TERR-ZERO",
                    name = "terrestrial, no reservoir offset",
                    offset = OffsetDist.Gaussian(0.0, 0.0),
                    rawText = "delta R = 0 (terrestrial)",
                    sourceRef = "reservoir-table:terrestrial zero line",
                ),
                ReservoirSpec(
                    reservoirCode = "R-COAST-MIX",
                    name = "coastal mixed marine/estuarine reservoir (bimodal expert prior)",
                    offset = OffsetDist.Mixture(
                        listOf(
                            OffsetDist.Component(0.55, 180.0, 45.0),
                            OffsetDist.Component(0.45, 320.0, 55.0),
                        ),
                    ),
                    rawText = "deltaR mixture {0.55:180±45; 0.45:320±55}",
                    sourceRef = "reservoir-table:coastal panel 2026, expert panel A",
                ),
            ),
            offsetBindings = listOf(
                OffsetBindingSpec("CS-A", "R-TERR-ZERO", "terrestrial twig", "binding-log row 1"),
                OffsetBindingSpec("CS-B", "R-COAST-MIX", "marine shell, uncertain coastal reservoir", "binding-log row 2"),
                OffsetBindingSpec("CS-C", "R-TERR-ZERO", "peat", "binding-log row 3"),
                OffsetBindingSpec("CS-D", "R-TERR-ZERO", "grain", "binding-log row 4"),
                OffsetBindingSpec("CS-E", "R-TERR-ZERO", "bulk (tentative)", "binding-log row 5"),
                OffsetBindingSpec("CS-F", "R-TERR-ZERO", "bone", "binding-log row 6"),
                OffsetBindingSpec("CS-G", "R-TERR-ZERO", "bone", "binding-log row 7"),
            ),
            epochSystems = listOf(
                EpochSystemSpec(
                    code = "EPOCH-ASTRO-BCECE",
                    sourceRef = "conventions-document:astronomical-year-and-BCE-CE",
                ),
            ),
            sequences = listOf(
                SequenceConstraintSpec(
                    chainCode = "CHAIN-1",
                    edges = listOf(
                        SequenceEdge("CS-G", "CS-F", "stratum III (G) underlies stratum II (F)", "field-log:section 4 step 9"),
                    ),
                    sourceRef = "field-log:section 4",
                ),
            ),
            exclusions = listOf(
                ExclusionSpec(
                    sampleCode = "CS-E",
                    reason = "Suspected modern root contamination in bulk sediment; excluded from calibration candidates pending chemistry review.",
                    sourceRef = "exclusion-memo:CS-E 2026-09-10",
                ),
            ),
        )
    }
}

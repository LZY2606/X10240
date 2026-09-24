package org.chrono.workbench.domain

import org.chrono.workbench.model.Curve
import org.chrono.workbench.model.CurvePoint
import org.chrono.workbench.model.SourceRef
import org.chrono.workbench.util.Ids

object SyntheticCurve {
    const val FAMILY = "GSB-SynCal"
    const val V1 = "GSB-SynCal13"
    const val V2 = "GSB-SynCal20"

    const val DOMAIN_OLDEST = 2080
    const val DOMAIN_YOUNGEST = 1500
    const val X0 = 1950.0
    const val PERIOD = 10.0
    const val HALF_WIDTH = 15.0
    const val BASE = 2060.0
    const val BRANCH_SLOPE = 3.0
    const val SIGMA_CONST = 15.0

    private fun mean(calBP: Int, amplitude: Double): Double {
        val d = calBP - X0
        val ad = kotlin.math.abs(d)
        if (ad <= HALF_WIDTH) {
            return BASE + amplitude * kotlin.math.cos(Math.PI * d / PERIOD)
        }
        val side = if (d > 0) 1.0 else -1.0
        return BASE + (ad - HALF_WIDTH) * BRANCH_SLOPE * side
    }

    fun rawRepresentation(version: String): String {
        val amp = if (version == V2) 48.0 else 60.0
        return "formula:mean(x)=BASE+$amp*cos(pi*(x-1950)/10) for |x-1950|<=15; " +
            "branches slope $BRANCH_SLOPE/yr; sigma=$SIGMA_CONST; " +
            "domain calBP $DOMAIN_OLDEST..$DOMAIN_YOUNGEST step 1"
    }

    fun build(version: String, supersedesId: String? = null, revisionOf: String? = null): Curve {
        val amplitude = if (version == V2) 48.0 else 60.0
        val points = (DOMAIN_OLDEST downTo DOMAIN_YOUNGEST).map { x ->
            CurvePoint(x, mean(x, amplitude), SIGMA_CONST.toDouble())
        }
        val raw = rawRepresentation(version)
        val id = Ids.curve(FAMILY, version, raw)
        val displayName = if (version == V2) "GSB Synthetic Calibration (2020 revision)"
        else "GSB Synthetic Calibration (2013 edition)"
        return Curve(
            id = id,
            family = FAMILY,
            version = version,
            displayName = displayName,
            domainOldest = DOMAIN_OLDEST,
            domainYoungest = DOMAIN_YOUNGEST,
            gridStep = 1,
            points = points,
            source = SourceRef(
                kind = "calibration-curve",
                name = displayName,
                locator = "offline-case://gsb-seat/curve/$version",
                snippet = raw
            ),
            rawRepresentation = raw,
            generatorFingerprint = "synthetic-formula-v1|$FAMILY|$version",
            supersedesCurveId = supersedesId,
            revisionOf = revisionOf
        )
    }
}

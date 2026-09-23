package org.chronobench.tool

import org.chronobench.casepack.BundledCurves
import org.chronobench.domain.SyntheticCurves
import java.io.File

fun main() {
    File("src/main/resources/curves/synthetic-intcal-v1.csv").writeText(BundledCurves.toCsv(SyntheticCurves.v1()))
    File("src/main/resources/curves/synthetic-intcal-v2.csv").writeText(BundledCurves.toCsv(SyntheticCurves.v2()))
    println("curve CSVs generated")
}

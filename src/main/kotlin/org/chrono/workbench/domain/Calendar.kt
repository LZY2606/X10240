package org.chrono.workbench.domain

import kotlinx.serialization.Serializable

@Serializable
data class YearLabel(val astronomical: Int, val text: String, val era: String)

object CalendarScales {
    fun astronomicalYear(calBP: Int): Int = 1950 - calBP + 1

    fun label(calBP: Int, zeroCalBP: Int = 1950): YearLabel {
        val ay = zeroCalBP - calBP + 1
        return when {
            ay > 0 -> YearLabel(ay, "${ay} CE", "CE")
            ay == 0 -> YearLabel(0, "1 BCE", "BCE")
            else -> YearLabel(ay, "${1 - ay} BCE", "BCE")
        }
    }

    fun spansBCECE(oldCalBP: Int, youngCalBP: Int, zeroCalBP: Int = 1950): Boolean =
        oldCalBP >= zeroCalBP && youngCalBP <= zeroCalBP
}

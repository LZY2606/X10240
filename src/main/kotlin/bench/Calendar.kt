package bench

/** 年代系统：cal BP（1950 纪元）与 BCE/CE 互转，无 0 年。 */
object Calendar {
    fun eraYear(calBp: Int, epochCE: Int = 1950): String {
        val ce = epochCE - calBp
        return if (ce >= 1) "$ce CE" else "${1 - ce} BCE"
    }

    fun rangeLabel(loCalBp: Int, hiCalBp: Int, epochCE: Int = 1950): String {
        val loEra = eraYear(hiCalBp, epochCE) // 大 calBP = 更早
        val hiEra = eraYear(loCalBp, epochCE)
        return "$loCalBp–$hiCalBp calBP（$loEra – $hiEra）"
    }

    fun crossesEra(loCalBp: Int, hiCalBp: Int, epochCE: Int = 1950): Boolean {
        val a = epochCE - loCalBp
        val b = epochCE - hiCalBp
        return (a >= 1) != (b >= 1)
    }
}

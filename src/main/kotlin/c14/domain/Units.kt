package c14.domain

import kotlinx.serialization.Serializable

/** A quantity that always carries its unit or coordinate benchmark. */
@Serializable
data class Quantity(val value: Double, val unit: String, val note: String = "") {
    init {
        require(unit.isNotBlank()) { "quantity must declare a unit/benchmark" }
    }
    fun format(digits: Int = 1): String = "%.${digits}f %s".format(value, unit)
}

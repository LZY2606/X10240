package gsb.rcarb.domain

import kotlinx.serialization.Serializable

/**
 * 人工排除/接受决定（针对某个候选日历区间）。
 * 决定本身是一等事件数据：可被后续决定撤销（supersedesDecision），
 * 但历史记录永不被覆盖。被否定的区间仍保留概率与拒绝原因。
 */
@Serializable
data class ExclusionDecision(
    val decisionId: String,
    val inputId: String,
    val intervalId: String,
    val action: Action,
    val reason: String,
    val decidedBy: String,
    val supersedesDecision: String?
) {
    enum class Action { Accept, Reject, ParameterAdjust }
}

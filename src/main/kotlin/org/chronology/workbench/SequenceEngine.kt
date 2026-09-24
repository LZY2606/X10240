package org.chronology.workbench

import kotlinx.serialization.Serializable

@Serializable
data class SequenceRelationSpec(
    val relationId: String,
    val versionId: String,
    val youngerSampleId: String,
    val olderSampleId: String,
    val rationale: String = "",
)

@Serializable
data class RelationAssessment(
    val relationId: String,
    val youngerSampleId: String,
    val olderSampleId: String,
    val orderingProbability: Double,
    val status: String,
    val marginalYounger: List<DensityPoint>,
    val marginalOlder: List<DensityPoint>,
)

@Serializable
data class SequenceCandidate(
    val candidateId: String,
    val relationId: String,
    val kind: String,
    val targetId: String,
    val description: String,
    val legal: Boolean,
    val probability: Double? = null,
)

@Serializable
data class SequenceComputation(
    val subjectId: String,
    val requestEventId: String,
    val resultEventId: String,
    val versionId: String,
    val status: String,
    val assessments: List<RelationAssessment>,
    val candidates: List<SequenceCandidate>,
    val diagnostics: List<String>,
    val directSources: List<String>,
    val sourceRanges: List<SourceRange>,
    val parameterFingerprint: String,
)

class SequenceEngine {
    fun evaluate(
        subjectId: String,
        requestEventId: String,
        relations: List<SequenceRelationSpec>,
        distributions: Map<String, CalibrationComputation>,
    ): SequenceComputation {
        val diagnostics = mutableListOf<String>()
        val nodes = relations.flatMap { listOf(it.youngerSampleId, it.olderSampleId) }.distinct()
        val missing = nodes.filterNot { distributions.containsKey(it) || distributions[it]?.density?.isNotEmpty() == true }
        if (missing.isNotEmpty()) return shell(subjectId, requestEventId, relations, "UNKNOWN", emptyList(), listOf("缺少校准分布: ${missing.joinToString()}"))

        val adjacency = relations.groupBy { it.youngerSampleId }.mapValues { entry -> entry.value.map { it.olderSampleId } }
        val cycle = findCycle(nodes, adjacency)
        if (cycle != null) {
            val candidates = relations.map {
                SequenceCandidate(
                    StableIds.stable("seq-candidate", listOf(requestEventId, it.relationId, "REMOVE_EDGE")),
                    it.relationId,
                    "REMOVE_RELATION",
                    it.relationId,
                    "移除层序边以打破环；测量值保持不变",
                    true,
                )
            }
            return shell(subjectId, requestEventId, relations, "CYCLE_CONFLICT", candidates, listOf("层序图存在环: ${cycle.joinToString(" → ")}"))
        }

        val ordered = topoSort(nodes, adjacency)
        val forward = LinkedHashMap<String, List<DensityPoint>>()
        for (node in ordered) {
            val independent = distributions.getValue(node).density
            val incoming = relations.filter { it.olderSampleId == node }
            val constrained = if (incoming.isEmpty()) independent else {
                val candidateLists = incoming.map { edge ->
                    val younger = forward.getValue(edge.youngerSampleId)
                    val youngerTail = tailProbability(younger)
                    independent.mapIndexed { index, point ->
                        point.copy(density = point.density * youngerTail[index])
                    }.renormalize()
                }
                combineGeometric(candidateLists)
            }
            forward[node] = constrained
        }

        val backward = LinkedHashMap<String, List<DensityPoint>>()
        for (node in ordered.asReversed()) {
            val outgoing = relations.filter { it.youngerSampleId == node }
            val base = forward.getValue(node)
            backward[node] = if (outgoing.isEmpty()) base else {
                val candidateLists = outgoing.map { edge ->
                    val older = backward[edge.olderSampleId] ?: forward.getValue(edge.olderSampleId)
                    val olderHead = headProbability(older)
                    base.mapIndexed { index, point -> point.copy(density = point.density * olderHead[index]) }.renormalize()
                }
                combineGeometric(candidateLists)
            }
        }

        val assessments = relations.map { relation ->
            val younger = distributions.getValue(relation.youngerSampleId).density
            val older = distributions.getValue(relation.olderSampleId).density
            val probability = orderingProbability(younger, older)
            RelationAssessment(
                relation.relationId,
                relation.youngerSampleId,
                relation.olderSampleId,
                probability,
                if (probability < 0.05) "CONTRADICTED" else "SUPPORTED",
                backward[relation.youngerSampleId].orEmpty(),
                backward[relation.olderSampleId].orEmpty(),
            )
        }

        val candidates = mutableListOf<SequenceCandidate>()
        for (assessment in assessments.filter { it.status == "CONTRADICTED" }) {
            val relation = relations.first { it.relationId == assessment.relationId }
            candidates.add(SequenceCandidate(StableIds.stable("seq-candidate", listOf(requestEventId, relation.relationId, "RELAX")), relation.relationId, "RELAX_RELATION", relation.relationId, "将该层序关系降为待证假设；不改动测量", true, assessment.orderingProbability))
            distributions[relation.youngerSampleId]?.credibleIntervals?.forEach { interval ->
                candidates.add(SequenceCandidate(StableIds.stable("seq-candidate", listOf(requestEventId, relation.relationId, "YOUNGER", interval.intervalId)), relation.relationId, "RESTRICT_YOUNGER_INTERVAL", interval.intervalId, "仅审阅年轻端区间 ${interval.startLabel}–${interval.endLabel}", true, interval.probability))
            }
            distributions[relation.olderSampleId]?.credibleIntervals?.forEach { interval ->
                candidates.add(SequenceCandidate(StableIds.stable("seq-candidate", listOf(requestEventId, relation.relationId, "OLDER", interval.intervalId)), relation.relationId, "RESTRICT_OLDER_INTERVAL", interval.intervalId, "仅审阅年长端区间 ${interval.startLabel}–${interval.endLabel}", true, interval.probability))
            }
            candidates.add(SequenceCandidate(StableIds.stable("seq-candidate", listOf(requestEventId, relation.relationId, "FORCE")), relation.relationId, "FORCE_RELATION", relation.relationId, "人工强制保留关系；这是与放松关系互斥的决定", true, assessment.orderingProbability))
        }

        val status = when {
            assessments.any { it.status == "CONTRADICTED" } -> "SEQUENCE_CONFLICT"
            else -> "CONSISTENT"
        }
        if (status == "SEQUENCE_CONFLICT") diagnostics.add("存在层序矛盾；候选是审阅调整，不会覆盖原始测量")
        return shell(subjectId, requestEventId, relations, status, candidates, diagnostics, assessments)
    }

    private fun shell(
        subjectId: String,
        requestEventId: String,
        relations: List<SequenceRelationSpec>,
        status: String,
        candidates: List<SequenceCandidate>,
        diagnostics: List<String>,
        assessments: List<RelationAssessment> = emptyList(),
    ): SequenceComputation {
        val direct = listOf(requestEventId) + relations.flatMap { listOf(it.versionId) }
        val fingerprint = StableIds.fingerprint(ALGORITHM_SEQUENCE, mapOf("request" to requestEventId, "relations" to relations.joinToString("|") { it.versionId }, "status" to status))
        val id = StableIds.stable("seq", listOf(subjectId, requestEventId, fingerprint))
        return SequenceComputation(subjectId, requestEventId, id, id, status, assessments, candidates, diagnostics, direct.distinct(), emptyList(), fingerprint)
    }

    private fun findCycle(nodes: List<String>, adjacency: Map<String, List<String>>): List<String>? {
        val state = HashMap<String, Int>()
        val stack = mutableListOf<String>()
        fun dfs(node: String): List<String>? {
            state[node] = 1
            stack.add(node)
            for (next in adjacency[node].orEmpty()) {
                when (state[next]) {
                    1 -> return stack.drop(stack.indexOf(next)) + next
                    0, null -> dfs(next)?.let { return it }
                }
            }
            stack.removeAt(stack.lastIndex)
            state[node] = 2
            return null
        }
        return nodes.firstNotNullOfOrNull { if (state[it] == null) dfs(it) else null }
    }

    private fun topoSort(nodes: List<String>, adjacency: Map<String, List<String>>): List<String> {
        val indegree = HashMap(nodes.associateWith { 0 }).toMutableMap()
        adjacency.values.flatten().forEach { indegree[it] = (indegree[it] ?: 0) + 1 }
        val queue = ArrayDeque(nodes.filter { indegree[it] == 0 })
        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            adjacency[node].orEmpty().forEach { next ->
                indegree[next] = (indegree[next] ?: 1) - 1
                if (indegree[next] == 0) queue.add(next)
            }
        }
        return result + nodes.filterNot { it in result }
    }

    private fun tailProbability(density: List<DensityPoint>): List<Double> {
        val suffix = DoubleArray(density.size)
        for (index in density.indices.reversed()) {
            val weight = if (index == 0) 0.5 else 1.0
            val next = if (index == density.lastIndex) 0.0 else suffix[index + 1]
            suffix[index] = density[index].density * weight + next
        }
        return suffix.toList()
    }

    private fun headProbability(density: List<DensityPoint>): List<Double> {
        val prefix = DoubleArray(density.size)
        for (index in density.indices) {
            val weight = if (index == density.lastIndex) 0.5 else 1.0
            val previous = if (index == 0) 0.0 else prefix[index - 1]
            prefix[index] = density[index].density * weight + previous
        }
        return prefix.toList()
    }

    private fun orderingProbability(younger: List<DensityPoint>, older: List<DensityPoint>): Double {
        val olderTail = tailProbability(older)
        return younger.mapIndexed { index, point ->
            val weight = if (index == 0 || index == younger.lastIndex) 0.5 else 1.0
            point.density * olderTail[index] * weight
        }.sum()
    }

    private fun List<DensityPoint>.renormalize(): List<DensityPoint> {
        val total = this.sumOf { it.density }
        return if (total <= 0) this else this.map { it.copy(density = it.density / total) }
    }

    private fun combineGeometric(lists: List<List<DensityPoint>>): List<DensityPoint> {
        if (lists.isEmpty()) return emptyList()
        if (lists.size == 1) return lists.first()
        return lists.first().indices.map { index ->
            val product = lists.fold(1.0) { acc, list -> acc * (list.getOrNull(index)?.density ?: 0.0) }
            lists.first()[index].copy(density = product)
        }.renormalize()
    }
}

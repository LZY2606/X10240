package gsb

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** 反例档案：基线 + 每次只改动一个直接来源，验证影响被限制在正确范围。 */
class CounterexampleArchiveTest {
    @TempDir lateinit var tmp: File

    private val dir = File("counterexamples")

    private fun loadSteps(f: File): JsonObject =
        JsonFmt.parseToJsonElement(f.readText()).jsonObject

    @Test
    fun `全部反例档案场景`() {
        val baseline = loadSteps(File(dir, "baseline.json"))
        val ceFiles = dir.listFiles { f -> f.name.startsWith("ce-") }!!.sortedBy { it.name }
        assertEquals(7, ceFiles.size, "七个反例场景文件")
        for (ceFile in ceFiles) {
            val ce = loadSteps(ceFile)
            val store = Store(File(tmp, ceFile.nameWithoutExtension + ".db").absolutePath, FixedClock())
            val wb = Workbench(store)
            // 1) 基线
            val baseReport = CasePack.execute(store, baseline)
            assertTrue(baseReport.ok, "${ceFile.name} 基线失败: ${baseReport.errors}")
            // 记录基线时各测量流的当前校准
            val before = mutableMapOf<String, DerivedRecord>()
            for (m in listOf("M1", "M2", "M3", "M4", "M5"))
                wb.latestCalibrationFor(m)?.let { before[m] = it }
            // 2) 只改动一个直接来源
            val changeDoc = buildJsonObject {
                putJsonArray("steps") {
                    ce["change"]!!.jsonArray.forEach { add(it) }
                }
            }
            val changeReport = CasePack.execute(store, changeDoc)
            assertTrue(changeReport.ok, "${ceFile.name} 改动失败: ${changeReport.errors}")
            // 3) 影响范围
            val expect = ce["expect"]!!.jsonObject
            val stale = store.staleIds()
            val staleStreams = expect["staleStreams"]!!.jsonArray.map { it.jsonPrimitive.content }
            val currentStreams = expect["currentStreams"]!!.jsonArray.map { it.jsonPrimitive.content }
            for (m in staleStreams) {
                val b = before[m]
                if (b != null) assertTrue(b.id in stale, "${ceFile.name}: $m 的基线校准应过期")
            }
            for (m in currentStreams) {
                val b = before[m]
                if (b != null) assertFalse(b.id in stale, "${ceFile.name}: $m 的基线校准不应过期")
            }
            // 4) 场景特定检查
            for (chk in expect["checks"]!!.jsonArray) {
                val c = chk.jsonObject
                when (c["type"]!!.jsonPrimitive.content) {
                    "calib" -> {
                        val stream = c["stream"]!!.jsonPrimitive.content
                        val r = CalibResult.fromJson(wb.latestCalibrationFor(stream)!!.result["calibration"]!!.jsonObject)
                        c["minPeaks"]?.jsonPrimitive?.int?.let { assertTrue(r.peaks.size >= it, "${ceFile.name}: $stream 峰数") }
                        c["minIntervals95"]?.jsonPrimitive?.int?.let { assertTrue(r.intervals95.size >= it, "${ceFile.name}: $stream 并列区间数") }
                        c["truncatedAtMin"]?.jsonPrimitive?.boolean?.let { assertEquals(it, r.truncatedAtMin, "${ceFile.name}: $stream 现代端截断") }
                        c["crossesEra"]?.jsonPrimitive?.boolean?.let {
                            assertEquals(it, r.intervals95.any { iv -> Era.crossesEraBoundary(iv.loBp, iv.hiBp) }, "${ceFile.name}: $stream BCE/CE 跨界") }
                        c["curveVersion"]?.jsonPrimitive?.content?.let {
                            assertEquals(it, wb.latestCalibrationFor(stream)!!.result["curveVersion"]!!.jsonPrimitive.content, "${ceFile.name}: $stream 曲线版本") }
                        if (c["disjoint"]?.jsonPrimitive?.boolean == true) {
                            val sorted = r.intervals95.sortedBy { it.loBp }
                            for (i in 1 until sorted.size)
                                assertTrue(sorted[i].loBp > sorted[i - 1].hiBp, "${ceFile.name}: $stream 区间不相交")
                        }
                    }
                    "strat" -> {
                        val a = StratAssessment.fromJson(
                            wb.latestStratAssessmentFor(c["constraint"]!!.jsonPrimitive.content)!!.result)
                        c["status"]?.jsonPrimitive?.content?.let { assertEquals(it, a.status, "${ceFile.name}: 层序状态") }
                        c["minCandidates"]?.jsonPrimitive?.int?.let { assertTrue(a.candidates.size >= it, "${ceFile.name}: 候选数") }
                    }
                    "measurementUnchanged" -> {
                        val stream = c["stream"]!!.jsonPrimitive.content
                        val head = store.headOf(stream)!!
                        assertNotNull(head.payload["c14AgeBP"], "${ceFile.name}: $stream 测量值仍在")
                        assertTrue(head.payload["raw"]!!.jsonPrimitive.content.isNotBlank(), "${ceFile.name}: $stream 原始表示保留")
                    }
                    "reproducible" -> {
                        val stream = c["stream"]!!.jsonPrimitive.content
                        val d1 = wb.latestCalibrationFor(stream)!!
                        val d2 = wb.calibrate(stream, "curve-main",
                            d1.deps.first { it.streamId.startsWith("offset") }.streamId, "era-default")
                        assertEquals(d1.id, d2.id, "${ceFile.name}: $stream 固定版本下重算得到同一身份（可重现）")
                    }
                    "calibChanged" -> {
                        val stream = c["stream"]!!.jsonPrimitive.content
                        val cur = wb.latestCalibrationFor(stream)!!
                        assertNotEquals(before[stream]!!.id, cur.id, "${ceFile.name}: $stream 校准已更新")
                        assertNotEquals(before[stream]!!.fingerprint, cur.fingerprint, "${ceFile.name}: $stream 参数指纹已变")
                    }
                    "staleViewable" -> {
                        val stream = c["stream"]!!.jsonPrimitive.content
                        val old = before[stream]!!
                        assertTrue(old.id in stale, "${ceFile.name}: $stream 旧结果过期")
                        assertNotNull(store.derivedById(old.id), "${ceFile.name}: $stream 旧结果仍可查看")
                    }
                    "adjudicationsKept" -> {
                        val stream = c["stream"]!!.jsonPrimitive.content
                        val d = wb.latestCalibrationFor(stream)!!
                        val (_, adjs) = store.adjudicationState(d.id)
                        assertEquals(c["count"]!!.jsonPrimitive.int, adjs.size,
                            "${ceFile.name}: 被采纳与未采纳的决定都保留")
                    }
                    else -> fail("${ceFile.name}: 未知检查类型 ${c["type"]}")
                }
            }
            store.close()
        }
    }
}

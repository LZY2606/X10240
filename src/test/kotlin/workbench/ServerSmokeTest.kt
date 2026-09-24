package workbench

import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class ServerSmokeTest {

    @TempDir
    lateinit var tmp: Path

    private fun testApp(block: suspend ApplicationTestBuilder.(db: String) -> Unit) =
        testApplication {
            application {
                workbenchModule("${tmp.toFile()}/server.db",
                    FixedClock(Instant.parse("2026-09-24T08:00:00Z")))
            }
            block("${tmp.toFile()}/server.db")
        }

    @Test
    fun `full review flow via pages - import candidates decide history export import`() = testApp {
        // 执行剧本（直接经服务层准备数据；库为临时库）
        Store("${tmp.toFile()}/server.db", FixedClock(Instant.parse("2026-09-24T08:00:00Z"))).use { s ->
            CasePack.runScript(Workbench(s))
        }
        client.get("/").apply {
            assertEquals(HttpStatusCode.OK, status)
            val html = bodyAsText()
            assertTrue(html.contains("项目总览"))
            assertTrue(html.contains("data-stable-id"))
        }
        val casePage = client.get("/casepack")
        assertTrue(casePage.bodyAsText().contains("碳年候选席"))

        // 找到一个多峰校准结果，打开详情并检查 SVG 与积分证据
        val state = Store("${tmp.toFile()}/server.db", FixedClock(Instant.parse("2026-09-24T08:00:00Z"))).use { it.state() }
        val multiCal = state.derived.values.first { d ->
            d.kind == "calibration" && d.payload.contains("parallel-calendar-intervals")
        }
        val detail = client.get("/derived/${multiCal.id}").bodyAsText()
        assertTrue(detail.contains("${multiCal.id}-density"))
        assertTrue(detail.contains("展开积分证据"))
        assertTrue(detail.contains("data-stable-id='fp-${multiCal.id}'"))
        val ev = client.get("/derived/${multiCal.id}/evidence?lo=2701&hi=2747&format=json")
        assertEquals(HttpStatusCode.OK, ev.status)
        assertTrue(ev.bodyAsText().contains("normalizationConstant"))

        // 历史视图
        client.get("/history").apply { assertEquals(HttpStatusCode.OK, status) }
        val history = client.get("/history/5").bodyAsText()
        assertTrue(history.contains("历史视图 @ 事件 5"))

        // 导出 -> 删除 -> 导入，身份保持
        val dump = client.get("/export").bodyAsText()
        assertTrue(dump.contains("\"schemaVersion\":\"rw-snapshot-1\""))
        client.post("/reset")
        Store("${tmp.toFile()}/server.db", FixedClock(Instant.parse("2026-09-24T08:00:00Z"))).use {
            assertEquals(0, it.eventCount())
        }
        val resp = client.submitForm("/import-snapshot", parameters {
            append("snapshot", dump)
        })
        assertEquals(HttpStatusCode.OK, resp.status)
        Store("${tmp.toFile()}/server.db", FixedClock(Instant.parse("2026-09-24T08:00:00Z"))).use { s ->
            val restored = s.state()
            assertTrue(restored.derived.containsKey(multiCal.id))
            assertTrue(restored.events.zipWithNext().all { (a, b) -> a.seq < b.seq })
        }
    }

    @Test
    fun `empty project pages render and malformed snapshot is rejected without crash`() = testApp {
        client.get("/").apply { assertEquals(HttpStatusCode.OK, status) }
        client.get("/casepack").apply { assertEquals(HttpStatusCode.OK, status) }
        val resp = client.submitForm("/import-snapshot", parameters { append("snapshot", "{not json") })
        assertTrue(resp.bodyAsText().contains("不是合法 JSON"))
    }
}

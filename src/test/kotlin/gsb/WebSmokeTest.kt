package gsb

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** 冒烟：真实 HTTP 服务走完 导入→候选→裁定→回放→导出→删库→导入。 */
class WebSmokeTest {
    @TempDir lateinit var tmp: File

    private fun get(port: Int, path: String): Pair<Int, String> {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        val code = conn.responseCode
        val body = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to body
    }

    @Test
    fun `页面走查与导出导入闭环`() {
        val db = File(tmp, "web.db").absolutePath
        val store = Store(db, FixedClock())
        val report = store.importJson(CasePack.loadBundled())
        assertTrue(report.ok, report.errors.joinToString())
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            Web.configure(this, store)
        }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        try {
            val (c1, home) = get(port, "/")
            assertEquals(200, c1); assertTrue(home.contains("样品日历年代候选席"), "总览叙事")
            assertTrue(home.contains("M1"), "样品链接")
            val (c2, m1) = get(port, "/samples/M1")
            assertEquals(200, c2)
            assertTrue(m1.contains("<svg"), "SVG 曲线与密度")
            assertTrue(m1.contains("积分证据"), "区间可展开积分证据")
            assertTrue(m1.contains("并列"), "并列日历区间")
            assertTrue(m1.contains("不同偏移假设") || m1.contains("偏移"), "偏移假设")
            val (c3, strat) = get(port, "/strat")
            assertEquals(200, c3)
            assertTrue(strat.contains("冲突") || strat.contains("conflict"), "层序矛盾可见")
            assertTrue(strat.contains("候选"), "候选调整可见")
            val (c4, m4) = get(port, "/samples/M4")
            assertEquals(200, c4); assertTrue(m4.contains("BCE") && m4.contains("CE"), "BCE/CE 标注")
            // 回放：回到曲线升版事件之前
            val upgradeSeq = store.events().first { it.type == Ev.INPUT_SUBMITTED &&
                it.payload["streamId"]?.jsonPrimitive?.content == "curve-main" &&
                it.payload["version"]?.jsonPrimitive?.int == 2 }.seq
            val (c5, replay) = get(port, "/samples/M1?at=${upgradeSeq - 1}")
            assertEquals(200, c5)
            assertTrue(replay.contains("回放视图"), "回放横幅")
            assertTrue(replay.contains("2020"), "回放显示当时的曲线版本")
            val (c6, events) = get(port, "/events")
            assertEquals(200, c6); assertTrue(events.contains("adjudication.recorded"), "裁定事件")
            assertTrue(events.contains("conflict.resolved") && events.contains("conflict.undone"), "解决与撤销")
            val (c7, export) = get(port, "/export")
            assertEquals(200, c7); assertTrue(export.contains("\"formatVersion\""))
            // 删库 → 新库 → 导入 → 身份保持
            val idsBefore = store.allDerived().map { it.id }.sorted()
            val seqsBefore = store.events().map { it.seq }
            store.close()
            val store2 = Store(File(tmp, "web2.db").absolutePath, FixedClock())
            val imp = store2.importJson(export)
            assertTrue(imp.ok, imp.errors.joinToString())
            assertEquals(idsBefore, store2.allDerived().map { it.id }.sorted(), "导出导入后身份保持")
            assertEquals(seqsBefore, store2.events().map { it.seq }, "事件顺序保持")
            store2.close()
        } finally {
            server.stop(500, 1000)
        }
    }
}

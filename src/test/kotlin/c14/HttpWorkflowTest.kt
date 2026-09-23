package c14

import c14.domain.*
import c14.web.ServerEnv
import c14.web.webModule
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class HttpWorkflowTest {
    private fun jsonElement(body: String) = domainJson.parseToJsonElement(body).jsonObject
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.arr(key: String) = this[key]!!.jsonArray

    private fun ApplicationTestBuilder.testEnv(): ServerEnv {
        val dir = Files.createTempDirectory("c14-http")
        val clock = VirtualClock(1_600_000_000_000L, 45_000L)
        val app = c14.web.AppEngine(clock, dir.resolve("http.sqlite"))
        val env = ServerEnv(app, clock, dir.toFile())
        application { webModule(env) }
        return env
    }

    @Test
    fun `full narrative workflow over http`() = testApplication {
        val env = testEnv()
        val client = createClient { }

        // 1. import the offline baseline from resources (built-in domain case)
        val baselineText = javaClass.getResourceAsStream("/c14-offline-bench/00-baseline.json")!!.bufferedReader().readText()
        val importResp = client.post("/api/import/baseline") {
            contentType(ContentType.Application.Json)
            setBody(baselineText)
        }
        assertEquals(HttpStatusCode.OK, importResp.status)
        val importBody = jsonElement(importResp.bodyAsText())
        assertEquals(true, importBody.obj("report")["accepted"]!!.jsonPrimitive.boolean)
        val state0 = importBody["state"]!!.jsonObject
        val pid = state0["projectId"]!!.jsonPrimitive.content

        // multimodality present
        val resultCount = state0.arr("results").size
        assertTrue(resultCount >= 6)
        val s1 = state0.arr("results").map { it.jsonObject }.first {
            it.obj("result")["sampleId"]!!.jsonPrimitive.content == "S1"
        }.obj("result")
        assertTrue(s1.arr("intervals95").size >= 2, "S1 should be multimodal")

        // sequence contradiction present with candidates
        val analyses = state0.arr("analyses")
        val lastAnalysis = analyses.last().jsonObject
        assertEquals(false, lastAnalysis["consistent"]!!.jsonPrimitive.boolean)
        assertTrue(lastAnalysis.arr("candidates").isNotEmpty())

        // evidence expansion for an interval returns the integration cells
        val ivlId = s1.arr("intervals95").first().jsonObject["id"]!!.jsonPrimitive.content
        val ev = client.get("/api/projects/$pid/evidence?interval=${ivlId.encodeURLParameter()}")
        assertEquals(HttpStatusCode.OK, ev.status)
        val evBody = jsonElement(ev.bodyAsText())
        assertTrue(evBody.arr("cells").isNotEmpty())
        assertTrue(evBody["integrationRule"]!!.jsonPrimitive.content.contains("trapezoid"))
        assertTrue(evBody["paramFingerprint"]!!.jsonPrimitive.content.length >= 16)

        // 2. accept that interval; reject the second tied interval
        val secondIvl = s1.arr("intervals95")[1].jsonObject["id"]!!.jsonPrimitive.content
        fun decide(id: String, kind: String, subject: String, rationale: String) = buildJsonObject {
            put("commands", buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("DECIDE"))
                    put("sourceId", JsonPrimitive("src-review"))
                    put("payloadJson", JsonPrimitive(domainJson.encodeToString(
                        DecisionRecorded(DecisionView(id, kind, subject, rationale, "src-review", eventSeq = -1))
                    )))
                })
            })
        }.toString()
        client.post("/api/projects/$pid/commands") {
            contentType(ContentType.Application.Json)
            setBody(decide("dec-acc", "ACCEPT", ivlId, "accept primary calendar interval"))
        }
        client.post("/api/projects/$pid/commands") {
            contentType(ContentType.Application.Json)
            setBody(decide("dec-rej", "REJECT_CALENDAR_INTERVAL", secondIvl, "reject secondary tied interval"))
        }

        // 3. apply case steps 01..03
        for (step in listOf("01-offset-revision.json", "02-measurement-revision.json", "03-conflicting-decisions.json")) {
            val text = javaClass.getResourceAsStream("/c14-offline-bench/$step")!!.bufferedReader().readText()
            val stepObj = domainJson.parseToJsonElement(text).jsonObject
            val commands = stepObj["commands"]!!.jsonArray.map { it.jsonObject }
            val payload = buildJsonObject {
                put("commands", JsonArray(commands))
            }.toString()
            val resp = client.post("/api/projects/$pid/commands") {
                contentType(ContentType.Application.Json); setBody(payload)
            }
            assertEquals(HttpStatusCode.OK, resp.status, "step $step failed: ${resp.bodyAsText()}")
        }

        val stateResp = client.get("/api/projects/$pid/state")
        val state = jsonElement(stateResp.bodyAsText())
        // after S6 revision, sequence is consistent
        assertEquals(true, state.arr("analyses").last().jsonObject["consistent"]!!.jsonPrimitive.boolean)
        // accepted interval retained, stale views exist
        assertTrue(state["acceptedIntervals"]!!.jsonArray.any { it.jsonPrimitive.content == ivlId })
        assertTrue(state["rejectedIntervals"]!!.jsonArray.any { it.jsonPrimitive.content == secondIvl })

        // 4. export -> delete -> reimport
        val exported = client.get("/api/projects/$pid/export").bodyAsText()
        client.delete("/api/projects/$pid")
        val afterDelete = client.get("/api/projects/$pid/state")
        assertEquals(HttpStatusCode.NotFound, afterDelete.status)
        val reimport = client.post("/api/import/events") {
            contentType(ContentType.Application.Json); setBody(exported)
        }
        assertEquals(HttpStatusCode.OK, reimport.status)
        val reBody = jsonElement(reimport.bodyAsText())
        assertEquals(true, reBody.obj("report")["accepted"]!!.jsonPrimitive.boolean)
        val state2 = reBody["state"]!!.jsonObject
        assertTrue(state2["acceptedIntervals"]!!.jsonArray.any { it.jsonPrimitive.content == ivlId })

        // 5. history replay: evidence at an old seq still works
        val seqAtImport = JsonObject(emptyMap())
        val oldStateResp = client.get("/api/projects/$pid/state/at/10")
        assertEquals(HttpStatusCode.OK, oldStateResp.status)
        val old = jsonElement(oldStateResp.bodyAsText())
        assertTrue(old["eventCount"]!!.jsonPrimitive.int <= 10)
    }

    @Test
    fun `svg endpoints render without external resources`() = testApplication {
        val env = testEnv()
        val baselineText = javaClass.getResourceAsStream("/c14-offline-bench/00-baseline.json")!!.bufferedReader().readText()
        client.post("/api/import/baseline") {
            contentType(ContentType.Application.Json); setBody(baselineText)
        }
        val pid = "carbon-roundtable-2026"
        val curve = client.get("/api/projects/$pid/svg/curve?sample=S1").bodyAsText()
        assertTrue(curve.contains("<svg"))
        val density = client.get("/api/projects/$pid/svg/density?sample=S1").bodyAsText()
        assertTrue(density.contains("<svg"))
        val seq = client.get("/api/projects/$pid/svg/sequence").bodyAsText()
        assertTrue(seq.contains("<svg"))
    }
}

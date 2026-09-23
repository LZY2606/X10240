package gsb

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class PackSource(
    val kind: String, val sourceId: String, val unit: String,
    val provenance: String, val raw: String
)

@Serializable
data class PackOp(
    val op: String,
    val kind: String = "", val sourceId: String = "", val unit: String = "",
    val provenance: String = "", val raw: String = "",
    val measurement: String = "", val verdict: String = "",
    val actor: String = "reviewer", val reason: String = ""
)

@Serializable
data class BaselinePack(val pack: String, val narrative: String, val sources: List<PackSource>)

@Serializable
data class RevisionsPack(val pack: String, val narrative: String, val ops: List<PackOp>)

/** Offline domain case pack: "carbon-date candidate bench - calibration curve,
 *  reservoir offset and multi-modal dating evidence". Loaded from classpath so
 *  demos and tests never touch the network. */
object CasePack {
    private fun resource(name: String): String =
        requireNotNull(CasePack::class.java.getResource("/casepack/$name")) { "missing casepack $name" }
            .readText()

    fun baseline(): BaselinePack = jsonFmt.decodeFromString(resource("baseline.json"))
    fun revisions(): RevisionsPack = jsonFmt.decodeFromString(resource("revisions.json"))

    fun applyBaseline(engine: Engine, actor: String = "casepack") {
        val pack = baseline()
        for (s in pack.sources) {
            engine.submitSource(s.kind, s.sourceId, s.unit, s.provenance, s.raw, actor)
        }
        engine.store.appendEvent(
            EventType.IMPORT_PACK, actor, engine.nowForEvent(),
            jsonFmt.encodeToString(mapOf("pack" to pack.pack, "sources" to pack.sources.size.toString()))
        )
    }

    /** Default full calibration run over the baseline inputs. */
    fun calibrateAll(engine: Engine, actor: String = "casepack") {
        val measurements = engine.store.latestSources().values.filter { it.kind == SourceKind.MEASUREMENT }
        for (m in measurements) {
            val offset = when (m.sourceId) {
                "KIA-55002" -> "offset-marine-local"
                else -> null
            }
            engine.calibrate(m.sourceId, "curve-labcal", offset, "era-bce-ce", actor)
        }
        val constraints = engine.store.latestSources().values.filter { it.kind == SourceKind.SEQUENCE }
        for (c in constraints) {
            runCatching { engine.sequenceCheck(c.sourceId, actor) }
        }
    }

    fun applyRevisions(engine: Engine, actor: String = "casepack") {
        val pack = revisions()
        for (op in pack.ops) {
            when (op.op) {
                "revise" -> engine.submitSource(op.kind, op.sourceId, op.unit, op.provenance, op.raw, actor)
                "adjudicate" -> {
                    val derived = latestCalibrationFor(engine, op.measurement)
                    if (derived != null) engine.adjudicate(derived.id, op.verdict, op.reason, op.actor)
                }
            }
        }
        // re-derive only what the revisions touched; stale records stay viewable
        calibrateAll(engine, actor)
        engine.store.appendEvent(
            EventType.IMPORT_PACK, actor, engine.nowForEvent(),
            jsonFmt.encodeToString(mapOf("pack" to pack.pack, "ops" to pack.ops.size.toString()))
        )
    }

    fun latestCalibrationFor(engine: Engine, measurementSourceId: String): DerivedRecord? =
        engine.store.derived().filter { it.kind == DerivedKind.CALIBRATION }
            .map { it to jsonFmt.decodeFromString(CalibrationResult.serializer(), it.payload) }
            .filter { (_, r) -> r.measurementSourceId == measurementSourceId }
            .maxByOrNull { it.first.createdSeq }?.first
}

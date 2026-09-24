package org.gsb.carbon.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.PolymorphicSerializer
import org.gsb.carbon.model.EventPayload
import org.gsb.carbon.model.ProjectCreated
import org.gsb.carbon.model.CurveRecorded
import org.gsb.carbon.model.CurveSuperseded
import org.gsb.carbon.model.MeasurementRecorded
import org.gsb.carbon.model.MeasurementRevised
import org.gsb.carbon.model.OffsetRecorded
import org.gsb.carbon.model.EraSystemRecorded
import org.gsb.carbon.model.SequenceRecorded
import org.gsb.carbon.model.ExclusionRecorded
import org.gsb.carbon.model.CalibrationComputed
import org.gsb.carbon.model.SequenceEvaluatedPayload
import org.gsb.carbon.model.DecisionMadePayload
import org.gsb.carbon.model.DecisionRevokedPayload
import org.gsb.carbon.model.ResultsInvalidated
import org.gsb.carbon.model.CaseImportRejected
import org.gsb.carbon.model.CaseStageRecorded

object JsonCodec {
    private val module = SerializersModule {
        polymorphic(EventPayload::class) {
            subclass(ProjectCreated::class, ProjectCreated.serializer())
            subclass(CurveRecorded::class, CurveRecorded.serializer())
            subclass(CurveSuperseded::class, CurveSuperseded.serializer())
            subclass(MeasurementRecorded::class, MeasurementRecorded.serializer())
            subclass(MeasurementRevised::class, MeasurementRevised.serializer())
            subclass(OffsetRecorded::class, OffsetRecorded.serializer())
            subclass(EraSystemRecorded::class, EraSystemRecorded.serializer())
            subclass(SequenceRecorded::class, SequenceRecorded.serializer())
            subclass(ExclusionRecorded::class, ExclusionRecorded.serializer())
            subclass(CalibrationComputed::class, CalibrationComputed.serializer())
            subclass(SequenceEvaluatedPayload::class, SequenceEvaluatedPayload.serializer())
            subclass(DecisionMadePayload::class, DecisionMadePayload.serializer())
            subclass(DecisionRevokedPayload::class, DecisionRevokedPayload.serializer())
            subclass(ResultsInvalidated::class, ResultsInvalidated.serializer())
            subclass(CaseImportRejected::class, CaseImportRejected.serializer())
            subclass(CaseStageRecorded::class, CaseStageRecorded.serializer())
        }
    }

    val json: Json = Json {
        serializersModule = module
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val pretty: Json = Json(json) { prettyPrint = true }

    fun encodePayload(payload: EventPayload): String =
        json.encodeToString(kotlinx.serialization.PolymorphicSerializer(EventPayload::class), payload)

    fun decodePayload(text: String): EventPayload =
        json.decodeFromString(PolymorphicSerializer(EventPayload::class), text)

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)
}

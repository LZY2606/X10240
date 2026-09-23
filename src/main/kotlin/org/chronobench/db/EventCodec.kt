package org.chronobench.db

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer as kxSerializer
import org.chronobench.events.CalibrationRunRecorded
import org.chronobench.events.CandidateRuled
import org.chronobench.events.ConflictDetected
import org.chronobench.events.CurveRegistered
import org.chronobench.events.CurveSuperseded
import org.chronobench.events.DecisionReversed
import org.chronobench.events.Event
import org.chronobench.events.EventJson
import org.chronobench.events.ExclusionDecisionSubmitted
import org.chronobench.events.JudgmentRecorded
import org.chronobench.events.MeasurementSubmitted
import org.chronobench.events.ProjectCreated
import org.chronobench.events.ReservoirHypothesisSubmitted
import org.chronobench.events.SequenceConstraintSubmitted
import org.chronobench.events.SequenceEvaluationRecorded

@OptIn(ExperimentalSerializationApi::class)
val eventModule: SerializersModule = SerializersModule {
    polymorphic(Event::class) {
        subclass(ProjectCreated::class)
        subclass(CurveRegistered::class)
        subclass(CurveSuperseded::class)
        subclass(MeasurementSubmitted::class)
        subclass(ReservoirHypothesisSubmitted::class)
        subclass(SequenceConstraintSubmitted::class)
        subclass(ExclusionDecisionSubmitted::class)
        subclass(CalibrationRunRecorded::class)
        subclass(SequenceEvaluationRecorded::class)
        subclass(CandidateRuled::class)
        subclass(DecisionReversed::class)
        subclass(JudgmentRecorded::class)
        subclass(ConflictDetected::class)
    }
}

val StoreJson: Json = Json(from = EventJson) {
    serializersModule = eventModule
    explicitNulls = true
}

object EventCodec {
    fun encode(event: Event): String = StoreJson.encodeToString(kxSerializer<Event>(), event)
    fun decode(payload: String): Event = StoreJson.decodeFromString(kxSerializer<Event>(), payload)
}

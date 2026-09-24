package org.chrono.workbench.case

import org.chrono.workbench.db.Payloads
import org.chrono.workbench.db.ProjectService
import org.chrono.workbench.domain.SyntheticCurve
import org.chrono.workbench.events.EventTypes
import org.chrono.workbench.model.*
import org.chrono.workbench.util.Ids

object CasePack {
    const val CASE_NAME = "碳年候选席：校准曲线、储库偏移与多峰年代证据"
    const val CASE_ID = "gsb-seat"

    private fun src(kind: String, name: String, file: String, snippet: String) =
        SourceRef(kind, name, "offline-case://gsb-seat/$file", snippet)

    private fun measure(
        seq: Int, lab: String, age: String, sigma: String, material: String,
        ageNum: Double? = age.toDoubleOrNull(), sigmaNum: Double? = sigma.toDoubleOrNull(),
        status: String = ConvertStatus.OK.name, diagnostics: List<String> = emptyList(),
        file: String = "laboratory-sheet.csv", revisionOf: String? = null,
        snippetOverride: String? = null
    ): Measurement {
        val snippet = snippetOverride ?: "$lab,$age,$sigma,14C BP,$material"
        return Measurement(
            id = Ids.measurement(lab, age, sigma, "offline-case://gsb-seat/$file", seq),
            labCode = lab,
            ageRaw = age,
            sigmaRaw = sigma,
            unit = "14C BP",
            material = material,
            ageBP = ageNum,
            sigmaBP = sigmaNum,
            convertStatus = status,
            diagnostics = diagnostics,
            source = src("radiocarbon-measurement", "Laboratory 14C sheet ($lab)", file, snippet),
            revisionOf = revisionOf,
            seq = seq
        )
    }

    data class Refs(
        val mm: String,
        val tie: String,
        val marine: String,
        val bcece: String,
        val edge: String,
        val unknown: String,
        val old: String,
        val young: String
    )

    fun load(service: ProjectService, tick: () -> Unit): Refs {
        service.openProject(
            CASE_ID, CASE_NAME,
            "Baseline keeps raw measurement+sigma, curve version, offset distribution, era system, sequence constraints and exclusions separately sourced.",
            replayTag = "case-0-open"
        ); tick()

        val curveV1 = SyntheticCurve.build(SyntheticCurve.V1)
        service.registerCurve(curveV1, replayTag = "case-1-curve-v1"); tick()

        val era = EraSystem(
            id = Ids.era("BCE/CE astronomical", 1950),
            name = "BCE/CE with astronomical zero (1 BCE = year 0)",
            convention = "BCE/CE",
            zeroCalBP = 1950,
            source = src("era-system", "Chronology team era convention", "era-system.md",
                "calBP 1950 is 1 BCE; astronomical year 0; no year zero in BCE/CE labels")
        )
        service.setEra(era, replayTag = "case-2-era"); tick()

        val mm = measure(1, "GSB-M01", "2090", "20", "charred barley grain")
        val tie = measure(2, "GSB-M02", "2060", "15", "short-lived twig")
        val marine = measure(3, "GSB-M03", "2490", "30", "marine shell (Mytilus)")
        val bcece = measure(4, "GSB-M04", "2050", "18", "cereal grain")
        val edge = measure(5, "GSB-M05", "2235", "15", "hearth charcoal")
        val unknown = measure(6, "GSB-M06", "2500", "15", "residual wood",
            diagnostics = listOf("Expected to lie beyond the old edge of the registered curve."))
        val seqOld = measure(7, "GSB-M07", "2200", "20", "basal peat")
        val seqYoung = measure(8, "GSB-M08", "1900", "20", "upper peat")
        listOf(mm, tie, marine, bcece, edge, unknown, seqOld, seqYoung).forEach {
            service.importMeasurement(it, replayTag = "case-3-import"); tick()
        }

        // reservoir offset distributions, separately sourced
        val marineRes = ReservoirOffset(
            id = Ids.reservoir(marine.id, "normal:400:40", 1),
            label = "Estuarine reservoir R(t)",
            kind = ReservoirKind.NORMAL,
            meanYears = 400.0,
            sdYears = 40.0,
            components = emptyList(),
            measurementId = marine.id,
            source = src("reservoir-offset-distribution", "Estuarine reservoir study, ΔR 400±40", "reservoir-deltaR.md",
                "R(t) ~ Normal(400 yr, 40 yr) combined in quadrature")
        )
        service.setReservoir(marineRes, replayTag = "case-4-reservoir-marine"); tick()

        val mixtureRes = ReservoirOffset(
            id = Ids.reservoir(marine.id, "mixture:0.6/360/30|0.4/460/30", 2),
            label = "Estuarine reservoir mixture (local vs marine end-member)",
            kind = ReservoirKind.MIXTURE,
            meanYears = 0.0,
            sdYears = 0.0,
            components = listOf(
                ReservoirComponent(0.6, 360.0, 30.0),
                ReservoirComponent(0.4, 460.0, 30.0)
            ),
            measurementId = marine.id,
            source = src("reservoir-offset-distribution", "Two-end-member estuarine mixing model", "reservoir-mixture.md",
                "0.6*N(360,30) + 0.4*N(460,30); weights are source-attributed")
        )
        service.setReservoir(mixtureRes, replayTag = "case-4b-reservoir-mixture"); tick()

        // sequence: a satisfied stratigraphy and a deliberately contradictory one
        val goodOrder = SequenceConstraint(
            id = Ids.constraint(seqOld.id, seqYoung.id, "older-than", 1),
            olderMeasurementId = seqOld.id,
            youngerMeasurementId = seqYoung.id,
            relation = "older-than",
            source = src("stratigraphy", "Field section log, peat unit A below unit B", "stratigraphy-section.md",
                "Basal peat GSB-M07 stratigraphically below upper peat GSB-M08")
        )
        service.setConstraint(goodOrder, replayTag = "case-5-sequence-good"); tick()

        val conflictOrder = SequenceConstraint(
            id = Ids.constraint(seqYoung.id, seqOld.id, "older-than", 2),
            olderMeasurementId = seqYoung.id,
            youngerMeasurementId = seqOld.id,
            relation = "older-than",
            source = src("stratigraphy", "Disputed field notebook page (later retracted)", "stratigraphy-disputed.md",
                "Notebook page claims upper peat below basal peat; retained as a contradiction to test conflict handling")
        )
        service.setConstraint(conflictOrder, replayTag = "case-5b-sequence-conflict"); tick()

        // initial exclusion decision (manual), later contradicted
        val initialExclude = ExclusionDecision(
            id = Ids.decision(unknown.id, "REJECTED", "outside curve, residual wood", 1),
            measurementId = unknown.id,
            decision = "REJECTED",
            reason = "Preliminary read: beyond curve range and material is residual wood.",
            decidedBy = "lead-archaeologist",
            source = src("exclusion-decision", "Lead archaeologist preliminary note", "decisions.md",
                "Exclude GSB-M06 pending curve upgrade")
        )
        service.decideExclusion(initialExclude, replayTag = "case-6-decision-reject"); tick()

        // calibrate everything against v1
        listOf(mm, tie, marine, bcece, edge, unknown, seqOld, seqYoung).forEach {
            service.requestCalibration(it.id, replayTag = "case-7-calibrate-v1"); tick()
        }
        service.evaluateSequence(goodOrder.id, replayTag = "case-8-seq-good"); tick()
        service.evaluateSequence(conflictOrder.id, replayTag = "case-8b-seq-conflict"); tick()

        // ---- branch 2: parameter revision (reservoir assumption) ----
        val revisedMixture = mixtureRes.copy(
            id = Ids.reservoir(marine.id, "mixture:0.5/350/25|0.5/470/25", 3),
            label = "Estuarine reservoir mixture (revised weights)",
            components = listOf(
                ReservoirComponent(0.5, 350.0, 25.0),
                ReservoirComponent(0.5, 470.0, 25.0)
            ),
            revisionOf = mixtureRes.id,
            seq = 3,
            source = src("reservoir-offset-distribution", "Revised mixing model after geochemistry review",
                "reservoir-mixture.md#revision-2", "0.5*N(350,25)+0.5*N(470,25); revision 2")
        )
        service.setReservoir(revisedMixture, replayTag = "case-9-reservoir-revision"); tick()
        service.requestCalibration(marine.id, replayTag = "case-9b-recal-marine"); tick()

        // ---- local source change: only sigma of one measurement corrected ----
        val mmRevised = measure(10, "GSB-M01", "2090", "26", "charred barley grain",
            file = "laboratory-sheet-correction.csv",
            revisionOf = mm.id,
            snippetOverride = "GSB-M01,2090,26,14C BP,charred barley grain [lab correction: sigma was under-reported]")
        service.reviseMeasurement(mmRevised, replayTag = "case-10-sigma-revision"); tick()
        service.requestCalibration(mm.id, replayTag = "case-10b-recal-mm"); tick()

        // ---- curve upgrade: v2 changes wiggle samples, branch samples unchanged ----
        val curveV2 = SyntheticCurve.build(SyntheticCurve.V2, supersedesId = curveV1.id)
        service.registerCurve(curveV2, superseded = true, replayTag = "case-11-curve-v2"); tick()
        listOf(mm, tie, marine, bcece, edge, unknown, seqOld, seqYoung).forEach {
            service.requestCalibration(it.id, replayTag = "case-12-calibrate-v2"); tick()
        }

        // ---- conflicting manual decision on the same object ----
        val reversedExclusion = ExclusionDecision(
            id = Ids.decision(unknown.id, "HELD_AS_UNKNOWN", "status retained as unknown under v2", 2),
            measurementId = unknown.id,
            decision = "HELD_AS_UNKNOWN",
            reason = "Reviewer keeps GSB-M06 as UNKNOWN candidate rather than rejecting; contradicts the earlier REJECTION.",
            decidedBy = "second-reviewer",
            source = src("exclusion-decision", "Second reviewer dissent note", "decisions.md#dissent",
                "Do not exclude; carry as unknown"),
            supersedesId = initialExclude.id
        )
        service.decideExclusion(reversedExclusion, replayTag = "case-13-conflict-decision"); tick()

        service.reviewCandidate(mm.id, "ACCEPT",
            "All three HPD intervals retained; central interval favoured by stratigraphic context but not averaged.",
            "lead-archaeologist", replayTag = "case-14-accept-mm"); tick()
        service.reviewCandidate(tie.id, "HOLD",
            "Two equal-probability calendar intervals; unique decision impossible without external evidence.",
            "lead-archaeologist", replayTag = "case-15-hold-tie"); tick()

        // undo the disputed (contradictory) sequence entry through a logged reversal
        service.reverseEvent(
            service.state.events.first { it.replayTag == "case-5b-sequence-conflict" }.eventId,
            replayTag = "case-16-undo-conflict"
        ); tick()

        return Refs(mm.id, tie.id, marine.id, bcece.id, edge.id, unknown.id, seqOld.id, seqYoung.id)
    }
}

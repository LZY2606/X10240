package org.chrono.workbench.util

object Ids {
    fun curve(family: String, version: String, rawRepresentation: String): String =
        Hashing.shortHash("curv", "$family|$version|$rawRepresentation")

    fun measurement(labCode: String, ageRaw: String, sigmaRaw: String, sourceLocator: String, seq: Int): String =
        Hashing.shortHash("meas", "$labCode|$ageRaw|$sigmaRaw|$sourceLocator|$seq")

    fun reservoir(measurementId: String, canonical: String, seq: Int): String =
        Hashing.shortHash("resv", "$measurementId|$canonical|$seq")

    fun constraint(a: String, b: String, relation: String, seq: Int): String =
        Hashing.shortHash("seqc", "$a|$b|$relation|$seq")

    fun era(convention: String, zeroCalBP: Int): String =
        Hashing.shortHash("eras", "$convention|$zeroCalBP")

    fun decision(measurementId: String, decision: String, reason: String, seq: Int): String =
        Hashing.shortHash("decn", "$measurementId|$decision|$reason|$seq")

    fun derived(canonicalFingerprint: String): String =
        Hashing.shortHash("derv", canonicalFingerprint)

    fun event(type: String, canonical: String): String =
        Hashing.shortHash("evnt", "$type|$canonical")
}

package com.zollpilot.service

import com.zollpilot.domain.ConfidenceLevel
import com.zollpilot.domain.ExtractedAttributes
import com.zollpilot.domain.RankedCandidate
import kotlin.math.abs

class ExplanationService {
    fun missingInformation(
        normalizedFamily: String?,
        attributes: ExtractedAttributes,
        rankedCandidates: List<RankedCandidate>,
    ): List<String> {
        val missing = linkedSetOf<String>()

        if (normalizedFamily == null) {
            missing += "Keine eindeutige Produktfamilie erkannt."
        }

        if (attributes.materialHints.isEmpty()) {
            missing += "Materialhinweis fehlt (z. B. Stahl, Inox, NBR, FKM)."
        }

        if (normalizedFamily in dimensionSensitiveFamilies && attributes.dimensions.isEmpty()) {
            missing += "Dimensionen für diese Familie fehlen."
        }

        if (normalizedFamily in normSensitiveFamilies && attributes.norms.isEmpty()) {
            missing += "Normreferenz fehlt (z. B. DIN oder ISO)."
        }

        val top = rankedCandidates.firstOrNull()
        val second = rankedCandidates.getOrNull(1)

        if (top == null) {
            missing += "Kein KN-Kandidat passt eindeutig."
            return missing.toList()
        }

        if (top.score < 45.0) {
            missing += "Hersteller- oder Modelldetails angeben, um die Confidence zu verbessern."
        }

        if (second != null && abs(top.score - second.score) < 8.0) {
            missing += "Top-Kandidaten liegen beim Score nah beieinander; mehr Spezifikation erforderlich."
        }

        return missing.toList()
    }

    fun confidence(rankedCandidates: List<RankedCandidate>): ConfidenceLevel {
        val top = rankedCandidates.firstOrNull() ?: return ConfidenceLevel.LOW
        val second = rankedCandidates.getOrNull(1)
        val gap = if (second == null) top.score else top.score - second.score

        return when {
            top.score >= 75.0 && gap >= 15.0 -> ConfidenceLevel.HIGH
            top.score >= 50.0 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    private companion object {
        val dimensionSensitiveFamilies = setOf(
            "schraube", "mutter", "o_ring", "wellendichtring", "lager", "kettenrad",
        )

        val normSensitiveFamilies = setOf(
            "schraube", "mutter", "lager",
        )
    }
}

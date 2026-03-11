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
            missing += "No clear product family detected."
        }

        if (attributes.materialHints.isEmpty()) {
            missing += "Material hint missing (e.g., steel, inox, NBR, FKM)."
        }

        if (normalizedFamily in dimensionSensitiveFamilies && attributes.dimensions.isEmpty()) {
            missing += "Dimensions missing for this family."
        }

        if (normalizedFamily in normSensitiveFamilies && attributes.norms.isEmpty()) {
            missing += "Norm reference missing (e.g., DIN or ISO)."
        }

        val top = rankedCandidates.firstOrNull()
        val second = rankedCandidates.getOrNull(1)

        if (top == null) {
            missing += "No catalog candidate matched clearly."
            return missing.toList()
        }

        if (top.score < 45.0) {
            missing += "Provide manufacturer/model details to improve confidence."
        }

        if (second != null && abs(top.score - second.score) < 8.0) {
            missing += "Top candidates are close in score; more specification needed."
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

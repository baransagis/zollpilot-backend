package com.zollpilot.service

import com.zollpilot.domain.CandidateDefinition
import com.zollpilot.domain.ExtractedAttributes
import com.zollpilot.domain.RankedCandidate
import kotlin.math.min

class ScoringService {
    fun score(
        candidates: List<CandidateDefinition>,
        normalizedFamily: String?,
        attributes: ExtractedAttributes,
        normalizedText: String,
    ): List<RankedCandidate> {
        return candidates
            .map { candidate ->
                var score = 0.0
                val reasons = mutableListOf<String>()

                if (normalizedFamily != null && candidate.familyMatches.contains(normalizedFamily)) {
                    score += 55
                    reasons += "erkannte KN-Familie '$normalizedFamily'"
                }

                val keywordOverlap = candidate.keywords.filter { keyword -> normalizedText.contains(keyword) }
                if (keywordOverlap.isNotEmpty()) {
                    score += min(24.0, keywordOverlap.size * 6.0)
                    reasons += "Keyword-Überschneidung: ${keywordOverlap.take(4).joinToString(", ")}"
                }

                val materialOverlap = candidate.materialHints.filter { material ->
                    attributes.materialHints.contains(material)
                }
                if (materialOverlap.isNotEmpty()) {
                    score += min(15.0, materialOverlap.size * 5.0)
                    reasons += "Materialhinweis: ${materialOverlap.joinToString(", ")}"
                }

                val normOverlap = candidate.normHints.filter { hint ->
                    attributes.norms.any { norm -> norm.contains(hint) }
                }
                if (normOverlap.isNotEmpty()) {
                    score += min(10.0, normOverlap.size * 4.0)
                    reasons += "Normhinweis: ${normOverlap.joinToString(", ")}"
                }

                if (candidate.dimensionRelevant && attributes.dimensions.isNotEmpty()) {
                    score += 5.0
                    reasons += "Dimensionen vorhanden"
                }

                val includeHits = candidate.includeTokens.filter { token -> normalizedText.contains(token) }
                if (includeHits.isNotEmpty()) {
                    score += min(9.0, includeHits.size * 3.0)
                    reasons += "Einschluss-Kriterium: ${includeHits.take(3).joinToString(", ")}"
                }

                val excludeHits = candidate.excludeTokens.filter { token -> normalizedText.contains(token) }
                if (excludeHits.isNotEmpty()) {
                    score -= excludeHits.size * 18.0
                    reasons += "Ausschluss-Kriterium-Treffer: ${excludeHits.take(3).joinToString(", ")}"
                }

                if (reasons.isEmpty()) {
                    reasons += "Geringe textuelle Evidenz"
                }

                RankedCandidate(
                    code = candidate.code,
                    label = candidate.label,
                    score = score.coerceIn(0.0, 100.0).round2(),
                    reasons = reasons,
                )
            }
            .sortedByDescending { it.score }
    }

    private fun Double.round2(): Double =
        kotlin.math.round(this * 100.0) / 100.0
}

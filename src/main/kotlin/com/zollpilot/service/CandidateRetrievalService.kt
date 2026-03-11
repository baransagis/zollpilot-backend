package com.zollpilot.service

import com.zollpilot.domain.CandidateDefinition
import com.zollpilot.domain.ExtractedAttributes

class CandidateRetrievalService(
    private val candidates: List<CandidateDefinition>,
) {
    fun retrieve(
        normalizedFamily: String?,
        attributes: ExtractedAttributes,
        normalizedText: String,
    ): List<CandidateDefinition> {
        val preselected = candidates.filter { candidate ->
            val familyHit = normalizedFamily != null && candidate.familyMatches.contains(normalizedFamily)
            val keywordHit = candidate.keywords.any { keyword -> normalizedText.contains(keyword) }
            val materialHit = candidate.materialHints.any { material -> attributes.materialHints.contains(material) }
            val includeHit = candidate.includeTokens.isNotEmpty() &&
                candidate.includeTokens.any { token -> normalizedText.contains(token) }

            familyHit || keywordHit || materialHit || includeHit
        }

        return preselected.ifEmpty { candidates }
    }
}

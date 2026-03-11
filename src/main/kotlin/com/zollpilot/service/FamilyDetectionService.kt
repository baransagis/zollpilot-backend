package com.zollpilot.service

import com.zollpilot.domain.ExtractedAttributes
import com.zollpilot.domain.FamilyDefinition

class FamilyDetectionService(families: List<FamilyDefinition>) {
    private val compiledFamilies = families.map { family ->
        CompiledFamily(
            definition = family,
            patterns = family.patterns.map { Regex(it) },
        )
    }

    fun detect(normalizedText: String, attributes: ExtractedAttributes): String? {
        val tokens = tokenize(normalizedText)

        val scored = compiledFamilies
            .map { family ->
                val keywordHits = family.definition.keywords.count { keyword ->
                    tokens.contains(keyword) || normalizedText.contains(keyword)
                }

                val patternHits = family.patterns.count { regex -> regex.containsMatchIn(normalizedText) }

                val materialBonus = when {
                    family.definition.id in sealingFamilies && attributes.materialHints.any { it in sealingMaterials } -> 1
                    else -> 0
                }

                val score = (keywordHits * 3) + (patternHits * 4) + family.definition.priority + materialBonus
                family.definition.id to score
            }
            .filter { (_, score) -> score > 0 }

        return scored.maxByOrNull { (_, score) -> score }?.first
    }

    private fun tokenize(text: String): Set<String> = text
        .split(Regex("[^a-z0-9_]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()

    private data class CompiledFamily(
        val definition: FamilyDefinition,
        val patterns: List<Regex>,
    )

    private companion object {
        val sealingFamilies = setOf("o_ring", "wellendichtring")
        val sealingMaterials = setOf("nbr", "fkm", "epdm", "ptfe")
    }
}

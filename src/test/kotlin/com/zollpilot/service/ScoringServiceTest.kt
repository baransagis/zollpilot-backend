package com.zollpilot.service

import com.zollpilot.domain.CandidateDefinition
import com.zollpilot.domain.ExtractedAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringServiceTest {
    private val scoringService = ScoringService()

    @Test
    fun `family and keyword matches rank screw candidate highest`() {
        val candidates = listOf(
            CandidateDefinition(
                code = "CN-7318-SCREWS",
                label = "Screws",
                familyMatches = listOf("schraube"),
                keywords = listOf("schraube", "gewinde"),
                materialHints = listOf("stahl"),
                normHints = listOf("din 933"),
                dimensionRelevant = true,
            ),
            CandidateDefinition(
                code = "CN-8482-BEARINGS",
                label = "Bearings",
                familyMatches = listOf("lager"),
                keywords = listOf("lager"),
            ),
        )

        val attributes = ExtractedAttributes(
            dimensions = listOf("m12x40"),
            norms = listOf("din 933"),
            materialHints = listOf("stahl"),
        )

        val ranked = scoringService.score(
            candidates = candidates,
            normalizedFamily = "schraube",
            attributes = attributes,
            normalizedText = "sechskantschraube din 933 m12x40 stahl",
        )

        assertEquals("CN-7318-SCREWS", ranked.first().code)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `exclude token applies penalty`() {
        val candidate = CandidateDefinition(
            code = "CN-TEST",
            label = "Test",
            familyMatches = listOf("schraube"),
            keywords = listOf("schraube"),
            excludeTokens = listOf("mutter"),
        )

        val ranked = scoringService.score(
            candidates = listOf(candidate),
            normalizedFamily = "schraube",
            attributes = ExtractedAttributes(),
            normalizedText = "schraube mutter set",
        )

        assertTrue(ranked.first().reasons.any { it.contains("Ausschluss-Kriterium") })
    }
}

package com.zollpilot.service

import com.zollpilot.domain.ExtractedAttributes
import com.zollpilot.domain.FamilyDefinition
import kotlin.test.Test
import kotlin.test.assertEquals

class FamilyDetectionServiceTest {
    private val service = FamilyDetectionService(
        families = listOf(
            FamilyDefinition(
                id = "schraube",
                keywords = listOf("schraube", "sechskantschraube"),
                patterns = listOf("\\bm\\d+(?:x\\d+)?\\b"),
            ),
            FamilyDefinition(
                id = "o_ring",
                keywords = listOf("o_ring", "dichtring"),
                patterns = listOf("\\b\\d+(?:[.,]\\d+)?x\\d+(?:[.,]\\d+)?mm\\b"),
            ),
        ),
    )

    @Test
    fun `detects screw family`() {
        val family = service.detect(
            normalizedText = "sechskantschraube din 933 m12x40 stahl",
            attributes = ExtractedAttributes(),
        )

        assertEquals("schraube", family)
    }

    @Test
    fun `detects o ring family`() {
        val family = service.detect(
            normalizedText = "o_ring 34,00x3,50mm nbr 70 shore a",
            attributes = ExtractedAttributes(materialHints = listOf("nbr")),
        )

        assertEquals("o_ring", family)
    }
}

package com.zollpilot.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizationServiceTest {
    private val service = NormalizationService()

    @Test
    fun `normalizes whitespace separators and key tokens`() {
        val input = "  O-Ring   DIN 3771  34,00 x 3,50 mm ; KA37DRS80S4TH  "

        val normalized = service.normalize(input)

        assertEquals("o_ring din 3771 34,00x3,50mm ka37drs80s4th", normalized)
        assertTrue(normalized.contains("din 3771"))
        assertTrue(normalized.contains("34,00x3,50mm"))
    }

    @Test
    fun `merges thread expression m 12 into m12`() {
        val normalized = service.normalize("Sechskantschraube M 12 x 40")

        assertTrue(normalized.contains("m12x40"))
    }
}

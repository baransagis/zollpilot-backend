package com.zollpilot.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CsvParserTest {
    private val parser = CsvParser()

    @Test
    fun `parses semicolon separated csv`() {
        val csv = """
            Materialnummer;Kurztext;Einkaufsbestelltext
            1001;Sechskantschraube DIN 933 M12x40;Stahl verzinkt
            1002;O-Ring 34,00x3,50mm;NBR 70 Shore A
        """.trimIndent()

        val rows = parser.parse(csv.byteInputStream())

        assertEquals(2, rows.size)
        assertEquals("1001", rows[0].materialNumber)
        assertEquals("NBR 70 Shore A", rows[1].purchaseText)
    }

    @Test
    fun `fails when required header is missing`() {
        val csv = """
            Materialnummer;Kurztext
            1001;Sechskantschraube DIN 933 M12x40
        """.trimIndent()

        assertFailsWith<CsvParsingException> {
            parser.parse(csv.byteInputStream())
        }
    }
}

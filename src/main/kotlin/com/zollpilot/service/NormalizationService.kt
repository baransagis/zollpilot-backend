package com.zollpilot.service

class NormalizationService {
    fun normalize(shortText: String, purchaseText: String): String {
        val combined = listOf(shortText, purchaseText)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        return normalize(combined)
    }

    fun normalize(rawText: String): String {
        var text = rawText.lowercase()

        text = text
            .replace('\u00D7', 'x')
            .replace(Regex("[\\t\\n\\r]+"), " ")
            .replace(Regex("\\bo\\s*[-_]?[ ]*ring\\b"), "o_ring")
            .replace(Regex("\\bm\\s+(\\d+(?:[.,]\\d+)?)\\b"), "m$1")
            .replace(Regex("(?<=\\d)\\s*[x]\\s*(?=\\d)"), "x")
            .replace(Regex("(\\d+(?:[.,]\\d+)?)\\s*(mm|cm|kg|g|m|nm)\\b"), "$1$2")
            .replace(Regex("[;|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return text
    }
}

package com.zollpilot.service

import com.zollpilot.domain.ExtractedAttributes

class ExtractionService {
    private val dimensionRegex = Regex("\\b\\d+(?:[.,]\\d+)?(?:x\\d+(?:[.,]\\d+)?){1,2}(?:mm|cm|m)?\\b")
    private val threadRegex = Regex("\\bm\\d+(?:x\\d+(?:[.,]\\d+)?)?\\b")
    private val normRegex = Regex("\\b(?:din|iso|en)\\s*\\d{2,6}\\b")
    private val hardnessRegex = Regex("\\b\\d{2,3}\\s*shore\\s*[ad]?\\b|\\b\\d{1,3}\\s*hrc\\b")
    private val modelRegex = Regex("\\b(?=[a-z0-9-]{6,}\\b)(?=.*[a-z])(?=.*\\d)[a-z0-9-]+\\b")

    private val materialHints = listOf(
        "stahl", "edelstahl", "inox", "v2a", "v4a", "messing", "aluminium", "alu",
        "guss", "bronze", "kunststoff", "pvc", "ptfe", "nbr", "fkm", "epdm",
    )

    private val surfaceHints = listOf(
        "verzinkt", "phosphatiert", "bruniert", "galvanisiert",
        "beschichtet", "eloxiert", "vernickelt", "verchromt",
    )

    fun extract(normalizedText: String): ExtractedAttributes {
        val dimensions = (dimensionRegex.findAll(normalizedText).map { it.value } +
            threadRegex.findAll(normalizedText).map { it.value })
            .distinct()
            .sorted()
            .toList()

        val norms = normRegex.findAll(normalizedText)
            .map { it.value.replace(Regex("\\s+"), " ") }
            .distinct()
            .sorted()
            .toList()

        val hardness = hardnessRegex.findAll(normalizedText)
            .map { it.value.replace(Regex("\\s+"), " ").trim() }
            .distinct()
            .sorted()
            .toList()

        val models = modelRegex.findAll(normalizedText)
            .map { it.value }
            .filterNot { it.startsWith("din") || it.startsWith("iso") || it.startsWith("en") }
            .distinct()
            .sorted()
            .toList()

        val materials = materialHints
            .filter { normalizedText.contains(it) }
            .distinct()

        val surfaces = surfaceHints
            .filter { normalizedText.contains(it) }
            .distinct()

        return ExtractedAttributes(
            dimensions = dimensions,
            norms = norms,
            materialHints = materials,
            hardnessHints = hardness,
            modelTokens = models,
            surfaceHints = surfaces,
        )
    }
}

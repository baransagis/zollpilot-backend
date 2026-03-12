package com.zollpilot.parser

import com.zollpilot.domain.MaterialInput
import java.io.InputStream

class CsvParsingException(message: String) : RuntimeException(message)

class CsvParser {
    private val requiredColumns = listOf("Materialnummer", "Kurztext", "Einkaufsbestelltext")

    fun parse(inputStream: InputStream): List<MaterialInput> {
        val raw = inputStream.bufferedReader().use { it.readText() }
        if (raw.isBlank()) {
            throw CsvParsingException("Der CSV-Inhalt ist leer.")
        }

        val delimiter = chooseDelimiter(raw)
        val records = parseRecords(raw, delimiter)
            .filter { row -> row.any { it.isNotBlank() } }

        if (records.isEmpty()) {
            throw CsvParsingException("Der CSV-Inhalt ist leer.")
        }

        val header = records.first().mapIndexed { index, column ->
            if (index == 0) column.removePrefix("\uFEFF").trim() else column.trim()
        }

        val headerIndex = header.withIndex().associate { it.value to it.index }
        val missingColumns = requiredColumns.filterNot { headerIndex.containsKey(it) }
        if (missingColumns.isNotEmpty()) {
            throw CsvParsingException("Erforderliche CSV-Spalten fehlen: ${missingColumns.joinToString(", ")}")
        }

        val materialNumberIdx = headerIndex.getValue("Materialnummer")
        val shortTextIdx = headerIndex.getValue("Kurztext")
        val purchaseTextIdx = headerIndex.getValue("Einkaufsbestelltext")

        val rows = records.drop(1).mapNotNull { row ->
            val normalizedRow = if (row.size < header.size) {
                row + List(header.size - row.size) { "" }
            } else {
                row
            }

            val materialNumber = normalizedRow.getOrNull(materialNumberIdx).orEmpty().trim()
            val shortText = normalizedRow.getOrNull(shortTextIdx).orEmpty().trim()
            val purchaseText = normalizedRow.getOrNull(purchaseTextIdx).orEmpty().trim()

            if (materialNumber.isBlank() && shortText.isBlank() && purchaseText.isBlank()) {
                null
            } else {
                MaterialInput(
                    materialNumber = materialNumber,
                    shortText = shortText,
                    purchaseText = purchaseText,
                )
            }
        }

        if (rows.isEmpty()) {
            throw CsvParsingException("Die CSV enthält keine Datenzeilen.")
        }

        return rows
    }

    private fun chooseDelimiter(raw: String): Char {
        val firstLine = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val semicolonCount = firstLine.count { it == ';' }
        val commaCount = firstLine.count { it == ',' }

        return if (semicolonCount > commaCount) ';' else ','
    }

    private fun parseRecords(content: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentValue = StringBuilder()

        var i = 0
        var insideQuotes = false

        while (i < content.length) {
            val char = content[i]

            when {
                char == '"' -> {
                    if (insideQuotes && i + 1 < content.length && content[i + 1] == '"') {
                        currentValue.append('"')
                        i++
                    } else {
                        insideQuotes = !insideQuotes
                    }
                }

                char == delimiter && !insideQuotes -> {
                    currentRow.add(currentValue.toString().trim())
                    currentValue.clear()
                }

                (char == '\n' || char == '\r') && !insideQuotes -> {
                    currentRow.add(currentValue.toString().trim())
                    currentValue.clear()
                    rows.add(currentRow.toList())
                    currentRow.clear()

                    if (char == '\r' && i + 1 < content.length && content[i + 1] == '\n') {
                        i++
                    }
                }

                else -> currentValue.append(char)
            }

            i++
        }

        if (currentValue.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentValue.toString().trim())
            rows.add(currentRow.toList())
        }

        return rows
    }
}

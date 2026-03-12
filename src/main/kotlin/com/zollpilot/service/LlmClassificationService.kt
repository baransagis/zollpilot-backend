package com.zollpilot.service

import com.zollpilot.config.LlmConfig
import com.zollpilot.domain.ClassificationResult
import com.zollpilot.domain.LlmClassificationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.http.HttpTimeoutException
import kotlin.math.roundToInt

class LlmClassificationService(
    private val config: LlmConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun isConfiguredForRequests(): Boolean = config.enabled && !config.apiKey.isNullOrBlank()

    suspend fun enrichBatch(localResults: List<ClassificationResult>): List<LlmClassificationResult?> {
        if (localResults.isEmpty()) return emptyList()
        if (!config.enabled) return List(localResults.size) { null }

        val apiKey = config.apiKey
        if (apiKey.isNullOrBlank()) {
            logger.warn("LLM enrichment skipped because no OpenAI API key was configured.")
            return List(localResults.size) { null }
        }

        val enriched = MutableList<LlmClassificationResult?>(localResults.size) { null }
        val promptItems = localResults.mapIndexed { index, result ->
            toPromptCluster(index, result)
        }
        val chunks = createAdaptiveChunks(promptItems)

        logger.info(
            "llm enrichment started items={} chunks={} maxItemsPerRequest={} maxPromptCharsPerRequest={} parallelRequests={}",
            promptItems.size,
            chunks.size,
            config.maxItemsPerRequest,
            config.maxPromptCharsPerRequest,
            config.parallelRequests,
        )

        val semaphore = Semaphore(config.parallelRequests)
        val chunkResults = coroutineScope {
            chunks.mapIndexed { chunkIndex, chunk ->
                async {
                    semaphore.withPermit {
                        processChunkWithRetries(chunkIndex, chunk, apiKey)
                    }
                }
            }.awaitAll()
        }

        chunkResults.forEach { chunkResult ->
            if (chunkResult.resultMap.size != chunkResult.expectedSize) {
                logger.warn(
                    "LLM enrichment returned {} items for chunk {} (expected {}).",
                    chunkResult.resultMap.size,
                    chunkResult.chunkIndex + 1,
                    chunkResult.expectedSize,
                )
            }

            chunkResult.chunk.forEach { promptItem ->
                val globalIndex = promptItem.id.toIntOrNull() ?: return@forEach
                if (globalIndex in enriched.indices) {
                    enriched[globalIndex] = chunkResult.resultMap[promptItem.id]
                }
            }
        }

        return enriched
    }

    private suspend fun processChunkWithRetries(
        chunkIndex: Int,
        chunk: List<PromptCluster>,
        apiKey: String,
    ): ChunkResult {
        var attempt = 0
        var lastFailureMessage: String? = null
        var lastFailure: Throwable? = null

        while (attempt <= config.maxRetriesPerChunk) {
            val result = runCatching {
                requestLlmBatch(chunk, apiKey)
            }

            if (result.isSuccess) {
                return ChunkResult(
                    chunkIndex = chunkIndex,
                    chunk = chunk,
                    expectedSize = chunk.size,
                    resultMap = result.getOrDefault(emptyMap()),
                )
            }

            val cause = result.exceptionOrNull()
            lastFailure = cause
            lastFailureMessage = cause?.message
            val timedOut = cause.isTimeoutError()
            val willRetry = attempt < config.maxRetriesPerChunk

            logger.warn(
                "LLM enrichment failed for chunk {} (size={}, attempt={}): {}",
                chunkIndex + 1,
                chunk.size,
                attempt + 1,
                lastFailureMessage ?: "unknown error",
            )

            if (willRetry) {
                val backoffMs = if (timedOut) 400L else 250L
                delay(backoffMs)
            }

            attempt += 1
        }

        val canSplit = chunk.size > 1 && lastFailure.isSplitRecoverableError()
        if (canSplit) {
            val middle = chunk.size / 2
            val left = chunk.subList(0, middle)
            val right = chunk.subList(middle, chunk.size)
            logger.warn(
                "LLM enrichment splitting chunk {} due parse/timeout errors: {} -> {} + {}",
                chunkIndex + 1,
                chunk.size,
                left.size,
                right.size,
            )
            val leftResult = processChunkWithRetries(chunkIndex, left, apiKey)
            val rightResult = processChunkWithRetries(chunkIndex, right, apiKey)

            return ChunkResult(
                chunkIndex = chunkIndex,
                chunk = chunk,
                expectedSize = chunk.size,
                resultMap = leftResult.resultMap + rightResult.resultMap,
            )
        }

        logger.warn(
            "LLM enrichment giving up for chunk {} (size={}) after {} attempts: {}",
            chunkIndex + 1,
            chunk.size,
            config.maxRetriesPerChunk + 1,
            lastFailureMessage ?: "unknown error",
        )

        return ChunkResult(
            chunkIndex = chunkIndex,
            chunk = chunk,
            expectedSize = chunk.size,
            resultMap = emptyMap(),
        )
    }

    private fun Throwable?.isTimeoutError(): Boolean {
        if (this == null) return false
        if (this is HttpTimeoutException) return true
        return (message ?: "").contains("timed out", ignoreCase = true)
    }

    private fun Throwable?.isSplitRecoverableError(): Boolean {
        if (this == null) return false
        if (this.isTimeoutError()) return true
        if (this is SerializationException) return true
        if (this is TruncatedModelOutputException) return true
        if (this is IncompleteModelOutputException) return true
        return (message ?: "").contains("Unexpected JSON token", ignoreCase = true)
    }

    private fun toPromptCluster(
        index: Int,
        result: ClassificationResult,
    ): PromptCluster = PromptCluster(
        id = index.toString(),
        materialNumber = truncate(result.materialNumber, 64),
        shortText = truncate(result.shortText, 220),
        purchaseText = truncate(result.purchaseText, 260),
        normalizedFamily = result.normalizedFamily?.let { truncate(it, 120) },
        normalizedText = truncate(result.normalizedText, 320),
        attributes = PromptAttributes(
            dimensions = result.attributes.dimensions.take(4).map { truncate(it, 64) },
            norms = result.attributes.norms.take(4).map { truncate(it, 64) },
            materialHints = result.attributes.materialHints.take(4).map { truncate(it, 64) },
            hardnessHints = result.attributes.hardnessHints.take(4).map { truncate(it, 64) },
            surfaceHints = result.attributes.surfaceHints.take(4).map { truncate(it, 64) },
        ),
        localCandidates = result.candidates.take(3).map { candidate ->
            PromptCandidate(
                code = candidate.code?.let { truncate(it, 32) },
                label = truncate(candidate.label, 110),
                score = candidate.score,
                reasons = candidate.reasons.take(2).map { truncate(it, 100) },
            )
        },
        missingInformation = result.missingInformation.take(3).map { truncate(it, 100) },
    )

    private fun createAdaptiveChunks(items: List<PromptCluster>): List<List<PromptCluster>> {
        if (items.isEmpty()) return emptyList()

        val chunks = mutableListOf<List<PromptCluster>>()
        val current = mutableListOf<PromptCluster>()
        var currentChars = 0

        items.forEach { item ->
            val itemChars = estimatePromptChars(item)
            val exceedsItems = current.size >= config.maxItemsPerRequest
            val exceedsChars = current.isNotEmpty() && (currentChars + itemChars > config.maxPromptCharsPerRequest)

            if (exceedsItems || exceedsChars) {
                chunks += current.toList()
                current.clear()
                currentChars = 0
            }

            current += item
            currentChars += itemChars
        }

        if (current.isNotEmpty()) {
            chunks += current.toList()
        }

        return chunks
    }

    private fun estimatePromptChars(item: PromptCluster): Int {
        val candidateReasonsChars = item.localCandidates
            .flatMap { it.reasons }
            .sumOf(String::length)
        val candidateLabelChars = item.localCandidates.sumOf { it.label.length + (it.code?.length ?: 0) }
        val attributesChars = item.attributes.dimensions.sumOf(String::length) +
            item.attributes.norms.sumOf(String::length) +
            item.attributes.materialHints.sumOf(String::length) +
            item.attributes.hardnessHints.sumOf(String::length) +
            item.attributes.surfaceHints.sumOf(String::length)
        val missingChars = item.missingInformation.sumOf(String::length)

        return item.materialNumber.length +
            item.shortText.length +
            item.purchaseText.length +
            item.normalizedText.length +
            (item.normalizedFamily?.length ?: 0) +
            candidateReasonsChars +
            candidateLabelChars +
            attributesChars +
            missingChars +
            220
    }

    private fun truncate(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        return value.take(maxChars).trimEnd() + "..."
    }

    private suspend fun requestLlmBatch(
        clusters: List<PromptCluster>,
        apiKey: String,
    ): Map<String, LlmClassificationResult> = withContext(Dispatchers.IO) {
        val maxCompletionTokens = estimateCompletionTokens(clusters.size)
        val requestPayload = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_completion_tokens", maxCompletionTokens)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                    },
                )
                add(
                    buildJsonObject {
                    put("role", "user")
                    put("content", json.encodeToString(PromptPayload(clusters)))
                    },
                )
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "cn_llm_classification_batch")
                    put("strict", true)
                    put("schema", llmResponseSchema())
                }
            }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.endpoint))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.encodeToString(JsonObject.serializer(), requestPayload),
                ),
            )
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "OpenAI request failed with status=${response.statusCode()} body=${response.body().take(500)}",
            )
        }

        val messageContent = extractMessageContent(response.body(), maxCompletionTokens)
            ?: throw IllegalStateException("OpenAI response does not include message content.")

        val parsed = decodeBatchResponse(messageContent)
        if (parsed.results.size < clusters.size) {
            throw IncompleteModelOutputException(
                "Model returned only ${parsed.results.size}/${clusters.size} results for chunk.",
            )
        }

        val promptById = clusters.associateBy { it.id }

        parsed.results.associate { item ->
            val normalizedHeadlines = item.candidateHeadlines
                .map(String::trim)
                .filter(String::isNotEmpty)
                .take(2)
                .map { normalizeCandidateHeadline(it) }
                .map { truncate(it, 110) }
            val fallbackLocalCode = promptById[item.id]
                ?.localCandidates
                ?.firstNotNullOfOrNull { normalizeCnCode(it.code) }
            val selectedCode = normalizeCnCode(item.selectedCnCode)
                ?: normalizedHeadlines.firstNotNullOfOrNull { extractFullCnCode(it) }
                ?: fallbackLocalCode

            item.id to LlmClassificationResult(
                headline = truncate(item.headline.trim(), 90),
                candidateHeadlines = normalizedHeadlines,
                selectedCnCode = selectedCode,
                explanation = truncate(item.explanation.trim(), 140),
                confidencePercent = item.confidencePercent.roundToInt().coerceIn(0, 100),
            )
        }
    }

    private fun estimateCompletionTokens(clusterCount: Int): Int {
        val estimated = 180 + (clusterCount * 140)
        return estimated.coerceAtMost(config.maxCompletionTokens).coerceAtLeast(200)
    }

    private fun extractMessageContent(body: String, maxCompletionTokens: Int): String? {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        if (finishReason == "length") {
            throw TruncatedModelOutputException(
                "Model output was truncated (finish_reason=length, max_completion_tokens=$maxCompletionTokens).",
            )
        }

        val message = choice["message"]?.jsonObject ?: return null

        val contentElement = message["content"] ?: return null
        return when (contentElement) {
            is JsonPrimitive -> contentElement.contentOrNull
            is JsonArray -> contentElement.firstOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull

            else -> null
        }
    }

    private fun decodeBatchResponse(content: String): LlmBatchResponse {
        val normalizedContent = normalizeJsonEnvelope(content)
        return try {
            json.decodeFromString<LlmBatchResponse>(normalizedContent)
        } catch (cause: SerializationException) {
            throw SerializationException(
                "Invalid model JSON output: ${cause.message}. Preview=${normalizedContent.take(240)}",
                cause,
            )
        }
    }

    private fun normalizeJsonEnvelope(content: String): String {
        val trimmed = content.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1)
        }

        return trimmed
    }

    private fun normalizeCandidateHeadline(raw: String): String {
        val fullCode = extractFullCnCode(raw) ?: return raw
        val withoutLeadingCode = raw.replace(
            Regex("^\\s*\\d(?:[\\d\\s]{2,15})\\s*-?\\s*"),
            "",
        ).trim()
        return if (withoutLeadingCode.isNotEmpty()) {
            "$fullCode - $withoutLeadingCode"
        } else {
            fullCode
        }
    }

    private fun normalizeCnCode(raw: String?): String? = extractFullCnCode(raw)

    private fun extractFullCnCode(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter(Char::isDigit)
        if (digits.length < 8) return null
        val cn8 = digits.take(8)
        return "${cn8.substring(0, 4)} ${cn8.substring(4, 6)} ${cn8.substring(6, 8)}"
    }

    private fun llmResponseSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("results") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("id") {
                            put("type", "string")
                        }
                        putJsonObject("headline") {
                            put("type", "string")
                        }
                        putJsonObject("candidateHeadlines") {
                            put("type", "array")
                            putJsonObject("items") {
                                put("type", "string")
                            }
                        }
                        putJsonObject("selectedCnCode") {
                            put(
                                "anyOf",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "string")
                                            put("pattern", "^\\d{4}\\s\\d{2}\\s\\d{2}$")
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("type", "null")
                                        },
                                    )
                                },
                            )
                        }
                        putJsonObject("explanation") {
                            put("type", "string")
                        }
                        putJsonObject("confidencePercent") {
                            put("type", "number")
                        }
                    }
                    put(
                        "required",
                        buildJsonArray {
                            add(JsonPrimitive("id"))
                            add(JsonPrimitive("headline"))
                            add(JsonPrimitive("candidateHeadlines"))
                            add(JsonPrimitive("selectedCnCode"))
                            add(JsonPrimitive("explanation"))
                            add(JsonPrimitive("confidencePercent"))
                        },
                    )
                }
            }
        }
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("results"))
            },
        )
    }

    @Serializable
    private data class PromptPayload(
        val clusters: List<PromptCluster>,
    )

    @Serializable
    private data class PromptCluster(
        val id: String,
        val materialNumber: String,
        val shortText: String,
        val purchaseText: String,
        val normalizedFamily: String? = null,
        val normalizedText: String,
        val attributes: PromptAttributes,
        val localCandidates: List<PromptCandidate>,
        val missingInformation: List<String>,
    )

    @Serializable
    private data class PromptAttributes(
        val dimensions: List<String> = emptyList(),
        val norms: List<String> = emptyList(),
        val materialHints: List<String> = emptyList(),
        val hardnessHints: List<String> = emptyList(),
        val surfaceHints: List<String> = emptyList(),
    )

    @Serializable
    private data class PromptCandidate(
        val code: String? = null,
        val label: String,
        val score: Double,
        val reasons: List<String> = emptyList(),
    )

    @Serializable
    private data class LlmBatchResponse(
        val results: List<LlmBatchItem> = emptyList(),
    )

    @Serializable
    private data class LlmBatchItem(
        val id: String,
        val headline: String,
        val candidateHeadlines: List<String> = emptyList(),
        val selectedCnCode: String? = null,
        val explanation: String,
        val confidencePercent: Double,
    )

    private data class ChunkResult(
        val chunkIndex: Int,
        val chunk: List<PromptCluster>,
        val expectedSize: Int,
        val resultMap: Map<String, LlmClassificationResult>,
    )

    private class TruncatedModelOutputException(message: String) : IllegalStateException(message)

    private class IncompleteModelOutputException(message: String) : IllegalStateException(message)

    companion object {
        private const val SYSTEM_PROMPT = """
Du bist ein Assistent fuer Zollklassifikation (CN).
Du erhaeltst Cluster mit lokalen, regelbasierten Kandidaten. Nutze diese als primaere Evidenz, aber nicht als harte Grenze.

Regeln:
1) Gib fuer jede input-id genau ein Ergebnis zurueck.
2) Alle natuerlichen Texte MUESSEN auf Deutsch sein: `headline`, `candidateHeadlines`, `explanation`.
3) Starte mit `localCandidates`. Wenn plausibel, bevorzuge sie.
4) Wenn `localCandidates` unplausibel/falsch/kapitel-falsch wirken, pruefe aktiv Alternativen aus anderen Kapiteln.
5) `selectedCnCode` MUSS eine volle 8-stellige CN im Format "NNNN NN NN" sein; sonst `null`.
6) `candidateHeadlines` sollen moeglichst mit voller 8-stelliger CN beginnen.
7) Keine Zufallsantworten: Begruende immer mit Text, Familie, Attributen und Kandidaten-Hinweisen.
8) Sehr knapp schreiben (Latenz): `headline` <= 90 Zeichen, max 2 `candidateHeadlines`, `explanation` <= 140 Zeichen.
9) `candidateHeadlines` Stil: "<CN_CODE> - <Label>: <kurzer Grund>".
10) Gib nur gueltiges JSON gemaess Schema aus:
   - Keine Markdown-Backticks
   - Keine Kommentare
   - Nur doppelte Anfuehrungszeichen fuer Strings
   - Newlines in Strings korrekt escapen
11) `confidencePercent` zwischen 0 und 100.
12) `confidencePercent` MUSS die KI-Sicherheit ausdruecken und darf NICHT einfach lokale Scores oder lokale Confidence spiegeln.
13) Kalibrierung fuer `confidencePercent`: >85 nur bei starker, konsistenter Evidenz; 60-85 bei plausibel aber mit Restunsicherheit; <60 bei mehreren offenen Punkten.
14) Ausgabe muss strikt dem JSON-Schema entsprechen.
"""
    }
}

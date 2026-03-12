package com.zollpilot.service

import com.zollpilot.config.LlmConfig
import com.zollpilot.domain.ClassificationResult
import com.zollpilot.domain.ExtractedAttributes
import com.zollpilot.domain.LlmClassificationResult
import com.zollpilot.domain.RankedCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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

    suspend fun enrichBatch(localResults: List<ClassificationResult>): List<LlmClassificationResult?> {
        if (localResults.isEmpty()) return emptyList()
        if (!config.enabled) return List(localResults.size) { null }

        val apiKey = config.apiKey
        if (apiKey.isNullOrBlank()) {
            logger.warn("LLM enrichment skipped because no OpenAI API key was configured.")
            return List(localResults.size) { null }
        }

        val enriched = MutableList<LlmClassificationResult?>(localResults.size) { null }
        val chunks = localResults.chunked(config.maxItemsPerRequest)

        chunks.forEachIndexed { chunkIndex, chunk ->
            val startIndex = chunkIndex * config.maxItemsPerRequest
            val promptItems = chunk.mapIndexed { localIndex, result ->
                PromptCluster(
                    id = (startIndex + localIndex).toString(),
                    materialNumber = result.materialNumber,
                    shortText = result.shortText,
                    purchaseText = result.purchaseText,
                    normalizedFamily = result.normalizedFamily,
                    normalizedText = result.normalizedText,
                    attributes = result.attributes,
                    localCandidates = result.candidates,
                    localConfidence = result.confidence.name.lowercase(),
                    missingInformation = result.missingInformation,
                )
            }

            val chunkEnrichment = runCatching {
                requestLlmBatch(promptItems, apiKey)
            }.onFailure { cause ->
                logger.warn(
                    "LLM enrichment failed for chunk {} (size={}): {}",
                    chunkIndex + 1,
                    chunk.size,
                    cause.message,
                )
            }.getOrDefault(emptyMap())
            if (chunkEnrichment.size != chunk.size) {
                logger.warn(
                    "LLM enrichment returned {} items for chunk {} (expected {}).",
                    chunkEnrichment.size,
                    chunkIndex + 1,
                    chunk.size,
                )
            }

            chunk.forEachIndexed { localIndex, _ ->
                val globalIndex = startIndex + localIndex
                enriched[globalIndex] = chunkEnrichment[globalIndex.toString()]
            }
        }

        return enriched
    }

    private suspend fun requestLlmBatch(
        clusters: List<PromptCluster>,
        apiKey: String,
    ): Map<String, LlmClassificationResult> = withContext(Dispatchers.IO) {
        val requestPayload = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
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

        val messageContent = extractMessageContent(response.body())
            ?: throw IllegalStateException("OpenAI response does not include message content.")

        val parsed = json.decodeFromString<LlmBatchResponse>(messageContent)
        parsed.results.associate { item ->
            item.id to LlmClassificationResult(
                headline = item.headline.trim(),
                candidateHeadlines = item.candidateHeadlines.map(String::trim).filter(String::isNotEmpty),
                selectedCnCode = item.selectedCnCode?.trim()?.takeIf { it.isNotEmpty() },
                explanation = item.explanation.trim(),
                confidencePercent = item.confidencePercent.roundToInt().coerceIn(0, 100),
            )
        }
    }

    private fun extractMessageContent(body: String): String? {
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?: return null

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
                                "type",
                                buildJsonArray {
                                    add(JsonPrimitive("string"))
                                    add(JsonPrimitive("null"))
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
        val attributes: ExtractedAttributes,
        val localCandidates: List<RankedCandidate>,
        val localConfidence: String,
        val missingInformation: List<String>,
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

    companion object {
        private const val SYSTEM_PROMPT = """
You are a customs classification assistant for CN numbers.
You receive clusters that already contain deterministic local candidates. Use these as primary evidence, but not as a hard constraint.

Rules:
1) Return one result for every input cluster id.
2) Start from localCandidates first. If they are plausible, prefer them.
3) If localCandidates appear wrong, incomplete, or from the wrong chapter, actively consider alternative CN candidates from other chapters.
4) For such alternative CN candidates, use the same candidateHeadlines style and clearly include short reasons why the chapter switch is justified.
5) selectedCnCode may be from localCandidates or from your alternative cross-chapter candidates; use null only when evidence is too weak.
6) Do not output random guesses: every candidate must be grounded in product text, family, attributes, and known CN chapter logic.
7) headline must be a compact one-liner for the cluster.
8) candidateHeadlines must list candidates in a consistent style:
   "<CN_CODE> - <Label>: <short reason>".
9) explanation must justify the selected CN using product text, family, attributes, and candidate reasons.
10) confidencePercent must be between 0 and 100.
11) Output must strictly follow the JSON schema.
"""
    }
}

package com.zollpilot.service

import com.zollpilot.domain.ClassificationResult
import com.zollpilot.domain.LlmProcessingStatus
import com.zollpilot.domain.MaterialInput
import org.slf4j.LoggerFactory

class ClassificationService(
    private val normalizationService: NormalizationService,
    private val extractionService: ExtractionService,
    private val familyDetectionService: FamilyDetectionService,
    private val candidateRetrievalService: CandidateRetrievalService,
    private val scoringService: ScoringService,
    private val explanationService: ExplanationService,
    private val llmClassificationService: LlmClassificationService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun isLlmConfigured(): Boolean = llmClassificationService.isConfiguredForRequests()

    suspend fun classify(material: MaterialInput): ClassificationResult {
        val localResult = classifyLocal(material).withLlmPendingIfConfigured()
        return completeLlmEnrichment(listOf(localResult)).firstOrNull() ?: localResult
    }

    suspend fun classifyBatch(materials: List<MaterialInput>): List<ClassificationResult> {
        val localResults = classifyBatchLocalFirst(materials)
        return completeLlmEnrichment(localResults)
    }

    fun classifyBatchLocalFirst(materials: List<MaterialInput>): List<ClassificationResult> =
        materials.map(::classifyLocal).map { it.withLlmPendingIfConfigured() }

    suspend fun completeLlmEnrichment(localResults: List<ClassificationResult>): List<ClassificationResult> {
        if (localResults.isEmpty()) return emptyList()
        if (!isLlmConfigured()) {
            return localResults.map { result ->
                if (result.llmStatus == LlmProcessingStatus.COMPLETED) {
                    result
                } else {
                    result.copy(llmStatus = LlmProcessingStatus.SKIPPED)
                }
            }
        }

        val llmResults = llmClassificationService.enrichBatch(localResults)
        return localResults.mapIndexed { index, result ->
            val llmResult = llmResults.getOrNull(index)
            if (llmResult != null) {
                result.copy(llm = llmResult, llmStatus = LlmProcessingStatus.COMPLETED)
            } else {
                result.copy(llmStatus = LlmProcessingStatus.FAILED)
            }
        }
    }

    private fun classifyLocal(material: MaterialInput): ClassificationResult {
        val normalizedText = normalizationService.normalize(material.shortText, material.purchaseText)
        val attributes = extractionService.extract(normalizedText)
        val normalizedFamily = familyDetectionService.detect(normalizedText, attributes)
        val retrievedCandidates = candidateRetrievalService.retrieve(normalizedFamily, attributes, normalizedText)
        val scoredCandidates = scoringService
            .score(retrievedCandidates, normalizedFamily, attributes, normalizedText)
            .take(3)
        val missingInformation = explanationService.missingInformation(normalizedFamily, attributes, scoredCandidates)
        val confidence = explanationService.confidence(scoredCandidates)

        logger.info(
            "classification materialNumber={} family={} topCandidate={} confidence={}",
            material.materialNumber,
            normalizedFamily ?: "unknown",
            scoredCandidates.firstOrNull()?.code ?: "none",
            confidence.name.lowercase(),
        )

        return ClassificationResult(
            materialNumber = material.materialNumber,
            shortText = material.shortText,
            purchaseText = material.purchaseText,
            normalizedText = normalizedText,
            normalizedFamily = normalizedFamily,
            attributes = attributes,
            candidates = scoredCandidates,
            missingInformation = missingInformation,
            confidence = confidence,
        )
    }

    private fun ClassificationResult.withLlmPendingIfConfigured(): ClassificationResult {
        return if (isLlmConfigured()) {
            copy(llmStatus = LlmProcessingStatus.PENDING)
        } else {
            copy(llmStatus = LlmProcessingStatus.SKIPPED)
        }
    }
}

package com.zollpilot.service

import com.zollpilot.domain.ClassificationResult
import com.zollpilot.domain.MaterialInput
import org.slf4j.LoggerFactory

class ClassificationService(
    private val normalizationService: NormalizationService,
    private val extractionService: ExtractionService,
    private val familyDetectionService: FamilyDetectionService,
    private val candidateRetrievalService: CandidateRetrievalService,
    private val scoringService: ScoringService,
    private val explanationService: ExplanationService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun classify(material: MaterialInput): ClassificationResult {
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

    fun classifyBatch(materials: List<MaterialInput>): List<ClassificationResult> = materials.map(::classify)
}

package com.zollpilot

import com.zollpilot.config.CatalogLoader
import com.zollpilot.config.loadAppConfig
import com.zollpilot.parser.CsvParser
import com.zollpilot.plugins.configureLogging
import com.zollpilot.plugins.configureRouting
import com.zollpilot.plugins.configureSerialization
import com.zollpilot.plugins.configureStatusPages
import com.zollpilot.service.CandidateRetrievalService
import com.zollpilot.service.ClassificationService
import com.zollpilot.service.ExplanationService
import com.zollpilot.service.ExtractionService
import com.zollpilot.service.FamilyDetectionService
import com.zollpilot.service.NormalizationService
import com.zollpilot.service.ScoringService
import io.ktor.server.application.Application
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("Application")

    val appConfig = loadAppConfig()
    val catalogData = CatalogLoader().load(appConfig)

    val normalizationService = NormalizationService()
    val extractionService = ExtractionService()
    val familyDetectionService = FamilyDetectionService(catalogData.families)
    val candidateRetrievalService = CandidateRetrievalService(catalogData.candidates)
    val scoringService = ScoringService()
    val explanationService = ExplanationService()

    val classificationService = ClassificationService(
        normalizationService = normalizationService,
        extractionService = extractionService,
        familyDetectionService = familyDetectionService,
        candidateRetrievalService = candidateRetrievalService,
        scoringService = scoringService,
        explanationService = explanationService,
    )

    configureLogging()
    configureSerialization()
    configureStatusPages()
    configureRouting(
        classificationService = classificationService,
        csvParser = CsvParser(),
        appConfig = appConfig,
        families = catalogData.families.map { it.id },
    )

    logger.info(
        "catalog loaded families={} candidates={} testCsvFile={}",
        catalogData.families.size,
        catalogData.candidates.size,
        appConfig.testCsvFile,
    )
}

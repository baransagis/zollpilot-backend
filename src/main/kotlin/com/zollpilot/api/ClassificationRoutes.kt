package com.zollpilot.api

import com.zollpilot.config.AppConfig
import com.zollpilot.domain.ErrorResponse
import com.zollpilot.domain.MaterialInput
import com.zollpilot.domain.MaterialRequest
import com.zollpilot.parser.CsvParser
import com.zollpilot.parser.CsvParsingException
import com.zollpilot.service.ClassificationService
import com.zollpilot.service.LlmEnrichmentCoordinator
import io.ktor.http.content.PartData
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

fun Route.classificationRoutes(
    classificationService: ClassificationService,
    llmEnrichmentCoordinator: LlmEnrichmentCoordinator,
    csvParser: CsvParser,
    appConfig: AppConfig,
) {
    val logger = LoggerFactory.getLogger("ClassificationRoutes")

    route("/classify") {
        post {
            val request = call.receive<MaterialRequest>().validate()
            val result = classificationService.classify(request.toDomain())
            call.respond(result)
        }

        post("/batch") {
            val requests = call.receive<List<MaterialRequest>>()
                .map { it.validate() }

            val results = classificationService.classifyBatch(requests.map { it.toDomain() })
            call.respond(results)
        }

        get("/llm-status/{jobId}") {
            val jobId = call.parameters["jobId"]?.trim().orEmpty()
            if (jobId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(message = "Missing llm job id."),
                )
                return@get
            }

            val status = llmEnrichmentCoordinator.status(jobId)
            if (status == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(message = "LLM job not found or expired."),
                )
                return@get
            }

            call.respond(status)
        }

        post("/upload-csv") {
            val multipart = call.receiveMultipart()
            var payload: ByteArray? = null
            var originalFileName: String? = null

            while (true) {
                val part = multipart.readPart() ?: break
                when (part) {
                    is PartData.FileItem -> {
                        originalFileName = part.originalFileName
                        payload = part.provider().toByteArray()
                    }

                    else -> Unit
                }
                part.dispose.invoke()
            }

            val bytes = payload ?: throw CsvParsingException("Im Multipart-Upload wurde keine CSV-Datei gefunden.")
            if (bytes.isEmpty()) {
                throw CsvParsingException("Die hochgeladene CSV-Datei ist leer.")
            }

            logger.info("csv upload fileName={} bytes={}", originalFileName ?: "unknown", bytes.size)

            val rows = csvParser.parse(ByteArrayInputStream(bytes))
            val localResults = classificationService.classifyBatchLocalFirst(rows)
            val response = llmEnrichmentCoordinator.start(localResults)
            call.respond(response)
        }

        get("/test-resource-csv") {
            val classLoader = call.application::class.java.classLoader
            val stream = classLoader.getResourceAsStream(appConfig.testCsvFile)
                ?: throw IllegalStateException("Resource '${appConfig.testCsvFile}' not found.")

            val materials = stream.use { csvParser.parse(it) }
            logger.info("resource csv file={} rows={}", appConfig.testCsvFile, materials.size)

            val localResults = classificationService.classifyBatchLocalFirst(materials)
            val response = llmEnrichmentCoordinator.start(localResults)
            call.respond(response)
        }
    }
}

private fun MaterialRequest.validate(): MaterialRequest {
    if (materialNumber.isBlank()) {
        throw IllegalArgumentException("Materialnummer darf nicht leer sein.")
    }

    if (shortText.isBlank() && purchaseText.isBlank()) {
        throw IllegalArgumentException("Mindestens eines von Kurztext oder Einkaufsbestelltext muss angegeben werden.")
    }

    return this
}

private fun MaterialRequest.toDomain(): MaterialInput = MaterialInput(
    materialNumber = materialNumber.trim(),
    shortText = shortText.trim(),
    purchaseText = purchaseText.trim(),
)

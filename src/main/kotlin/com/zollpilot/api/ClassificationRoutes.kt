package com.zollpilot.api

import com.zollpilot.config.AppConfig
import com.zollpilot.domain.MaterialInput
import com.zollpilot.domain.MaterialRequest
import com.zollpilot.parser.CsvParser
import com.zollpilot.parser.CsvParsingException
import com.zollpilot.service.ClassificationService
import io.ktor.http.content.PartData
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
            val results = classificationService.classifyBatch(rows)
            call.respond(results)
        }

        get("/test-resource-csv") {
            val classLoader = call.application::class.java.classLoader
            val stream = classLoader.getResourceAsStream(appConfig.testCsvFile)
                ?: throw IllegalStateException("Resource '${appConfig.testCsvFile}' not found.")

            val materials = stream.use { csvParser.parse(it) }
            logger.info("resource csv file={} rows={}", appConfig.testCsvFile, materials.size)

            val results = classificationService.classifyBatch(materials)
            call.respond(results)
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

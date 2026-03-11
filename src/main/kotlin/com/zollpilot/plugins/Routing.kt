package com.zollpilot.plugins

import com.zollpilot.api.catalogRoutes
import com.zollpilot.api.classificationRoutes
import com.zollpilot.api.healthRoutes
import com.zollpilot.config.AppConfig
import com.zollpilot.parser.CsvParser
import com.zollpilot.service.ClassificationService
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureRouting(
    classificationService: ClassificationService,
    csvParser: CsvParser,
    appConfig: AppConfig,
    families: List<String>,
) {
    routing {
        get("/") {
            call.respondRedirect(url = "/ui/results", permanent = false)
        }

        get("/ui/results") {
            val html = call.application::class.java.classLoader.readResourceText("static/results.html")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/ui/results.html") {
            val html = call.application::class.java.classLoader.readResourceText("static/results.html")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/test-resource-csv") {
            call.respondRedirect(url = "/api/v1/classify/test-resource-csv", permanent = false)
        }

        route("/api/v1") {
            get("/test-resource-csv") {
                call.respondRedirect(url = "/api/v1/classify/test-resource-csv", permanent = false)
            }

            healthRoutes()
            classificationRoutes(classificationService, csvParser, appConfig)
            catalogRoutes(families)
        }
    }
}

private fun ClassLoader.readResourceText(path: String): String {
    val stream = getResourceAsStream(path)
        ?: throw IllegalStateException("Resource '$path' not found.")

    return stream.bufferedReader().use { it.readText() }
}

package com.zollpilot.plugins

import com.zollpilot.api.catalogRoutes
import com.zollpilot.api.classificationRoutes
import com.zollpilot.api.healthRoutes
import com.zollpilot.config.AppConfig
import com.zollpilot.parser.CsvParser
import com.zollpilot.service.ClassificationService
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondBytes
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
            val html = call.application::class.java.classLoader.readResourceText("static/upload.html")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/ui") {
            val html = call.application::class.java.classLoader.readResourceText("static/upload.html")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/ui/upload") {
            val html = call.application::class.java.classLoader.readResourceText("static/upload.html")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/ui/upload.html") {
            val html = call.application::class.java.classLoader.readResourceText("static/upload.html")
            call.respondText(html, ContentType.Text.Html)
        }

        get("/ui/results") {
            call.respondRedirect(url = "/ui", permanent = false)
        }

        get("/ui/results.html") {
            call.respondRedirect(url = "/ui", permanent = false)
        }

        get("/static/upload.css") {
            val css = call.application::class.java.classLoader.readResourceBytes("static/upload.css")
            call.respondBytes(css, ContentType.Text.CSS)
        }

        get("/static/upload.js") {
            val js = call.application::class.java.classLoader.readResourceBytes("static/upload.js")
            call.respondBytes(js, ContentType.Application.JavaScript)
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

private fun ClassLoader.readResourceBytes(path: String): ByteArray {
    val stream = getResourceAsStream(path)
        ?: throw IllegalStateException("Resource '$path' not found.")

    return stream.readBytes()
}

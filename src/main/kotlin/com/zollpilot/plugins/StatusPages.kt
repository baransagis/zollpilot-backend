package com.zollpilot.plugins

import com.zollpilot.domain.ErrorResponse
import com.zollpilot.parser.CsvParsingException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

fun Application.configureStatusPages() {
    val logger = LoggerFactory.getLogger("StatusPages")

    install(StatusPages) {
        exception<CsvParsingException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Invalid CSV payload.", details = cause.message),
            )
        }

        exception<JsonConvertException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Invalid JSON payload.", details = cause.message),
            )
        }

        exception<ContentTransformationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Invalid request payload.", details = cause.message),
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(message = "Invalid request.", details = cause.message),
            )
        }

        exception<Throwable> { call, cause ->
            logger.error("unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(message = "Unexpected server error."),
            )
        }
    }
}

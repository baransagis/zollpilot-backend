package com.zollpilot.api

import com.zollpilot.domain.HealthResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.healthRoutes() {
    route("/health") {
        get {
            call.respond(HealthResponse())
        }
    }
}

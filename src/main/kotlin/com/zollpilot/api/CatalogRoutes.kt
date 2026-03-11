package com.zollpilot.api

import com.zollpilot.domain.FamiliesResponse
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.catalogRoutes(families: List<String>) {
    route("/catalog") {
        get("/families") {
            call.respond(FamiliesResponse(families = families.sorted()))
        }
    }
}

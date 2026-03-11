package com.zollpilot.config

import io.ktor.server.application.Application
import io.ktor.server.config.propertyOrNull

data class AppConfig(
    val testCsvFile: String,
    val familiesPath: String,
    val candidatesPath: String,
)

fun Application.loadAppConfig(): AppConfig {
    val config = environment.config

    return AppConfig(
        testCsvFile = config.propertyOrNull("app.testCsvFile")?.getString() ?: "materials.csv",
        familiesPath = config.propertyOrNull("app.catalog.familiesPath")?.getString() ?: "catalog/families.json",
        candidatesPath = config.propertyOrNull("app.catalog.candidatesPath")?.getString() ?: "catalog/cn-candidates.json",
    )
}

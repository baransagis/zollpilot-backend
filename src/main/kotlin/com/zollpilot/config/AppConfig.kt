package com.zollpilot.config

import io.ktor.server.application.Application
import io.ktor.server.config.propertyOrNull

data class AppConfig(
    val testCsvFile: String,
    val familiesPath: String,
    val candidatesPath: String,
    val llm: LlmConfig,
)

data class LlmConfig(
    val enabled: Boolean,
    val apiKey: String?,
    val endpoint: String,
    val model: String,
    val maxItemsPerRequest: Int,
    val timeoutSeconds: Long,
    val temperature: Double,
)

fun Application.loadAppConfig(): AppConfig {
    val config = environment.config

    return AppConfig(
        testCsvFile = config.propertyOrNull("app.testCsvFile")?.getString() ?: "materials.csv",
        familiesPath = config.propertyOrNull("app.catalog.familiesPath")?.getString() ?: "catalog/families.json",
        candidatesPath = config.propertyOrNull("app.catalog.candidatesPath")?.getString() ?: "catalog/cn-candidates.json",
        llm = LlmConfig(
            enabled = config.propertyOrNull("app.llm.enabled")?.getString()?.toBooleanStrictOrNull() ?: true,
            apiKey = (
                config.propertyOrNull("app.llm.apiKey")?.getString()
                    ?: System.getenv("OPENAI_API_KEY")
                )?.trim()?.takeIf { it.isNotEmpty() },
            endpoint = config.propertyOrNull("app.llm.endpoint")?.getString()
                ?: "https://api.openai.com/v1/chat/completions",
            model = config.propertyOrNull("app.llm.model")?.getString() ?: "gpt-4.1-mini",
            maxItemsPerRequest = (
                config.propertyOrNull("app.llm.maxItemsPerRequest")?.getString()?.toIntOrNull() ?: 20
                ).coerceAtLeast(1),
            timeoutSeconds = (
                config.propertyOrNull("app.llm.timeoutSeconds")?.getString()?.toLongOrNull() ?: 45L
                ).coerceAtLeast(5L),
            temperature = (
                config.propertyOrNull("app.llm.temperature")?.getString()?.toDoubleOrNull() ?: 0.1
                ).coerceAtLeast(0.0),
        ),
    )
}

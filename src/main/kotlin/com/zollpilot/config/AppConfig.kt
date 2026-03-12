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
    val maxPromptCharsPerRequest: Int,
    val parallelRequests: Int,
    val maxRetriesPerChunk: Int,
    val maxCompletionTokens: Int,
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
            model = config.propertyOrNull("app.llm.model")?.getString() ?: "gpt-4o-mini",
            maxItemsPerRequest = (
                config.propertyOrNull("app.llm.maxItemsPerRequest")?.getString()?.toIntOrNull() ?: 12
                ).coerceAtLeast(1),
            maxPromptCharsPerRequest = (
                config.propertyOrNull("app.llm.maxPromptCharsPerRequest")?.getString()?.toIntOrNull() ?: 14000
                ).coerceAtLeast(2000),
            parallelRequests = (
                config.propertyOrNull("app.llm.parallelRequests")?.getString()?.toIntOrNull() ?: 6
                ).coerceIn(1, 16),
            maxRetriesPerChunk = (
                config.propertyOrNull("app.llm.maxRetriesPerChunk")?.getString()?.toIntOrNull() ?: 1
                ).coerceAtLeast(0),
            maxCompletionTokens = (
                config.propertyOrNull("app.llm.maxCompletionTokens")?.getString()?.toIntOrNull() ?: 3600
                ).coerceAtLeast(100),
            timeoutSeconds = (
                config.propertyOrNull("app.llm.timeoutSeconds")?.getString()?.toLongOrNull() ?: 150L
                ).coerceAtLeast(5L),
            temperature = (
                config.propertyOrNull("app.llm.temperature")?.getString()?.toDoubleOrNull() ?: 0.0
                ).coerceAtLeast(0.0),
        ),
    )
}

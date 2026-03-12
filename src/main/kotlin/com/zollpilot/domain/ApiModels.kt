package com.zollpilot.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialRequest(
    val materialNumber: String,
    val shortText: String,
    val purchaseText: String,
)

@Serializable
data class ErrorResponse(
    val message: String,
    val details: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String = "UP",
)

@Serializable
data class FamiliesResponse(
    val families: List<String>,
)

@Serializable
enum class LlmJobStatus {
    @SerialName("processing")
    PROCESSING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("skipped")
    SKIPPED,
}

@Serializable
data class ClassificationBatchResponse(
    val results: List<ClassificationResult>,
    val llmJobId: String? = null,
    val llmJobStatus: LlmJobStatus = LlmJobStatus.SKIPPED,
)

@Serializable
data class LlmJobStatusResponse(
    val llmJobId: String,
    val llmJobStatus: LlmJobStatus,
    val results: List<ClassificationResult> = emptyList(),
    val message: String? = null,
)

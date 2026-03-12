package com.zollpilot.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MaterialInput(
    val materialNumber: String,
    val shortText: String,
    val purchaseText: String,
)

@Serializable
data class ExtractedAttributes(
    val dimensions: List<String> = emptyList(),
    val norms: List<String> = emptyList(),
    val materialHints: List<String> = emptyList(),
    val hardnessHints: List<String> = emptyList(),
    val modelTokens: List<String> = emptyList(),
    val surfaceHints: List<String> = emptyList(),
)

@Serializable
data class RankedCandidate(
    val code: String?,
    val label: String,
    val score: Double,
    val reasons: List<String>,
)

@Serializable
enum class ConfidenceLevel {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW,
}

@Serializable
data class ClassificationResult(
    val materialNumber: String,
    val shortText: String,
    val purchaseText: String,
    val normalizedText: String,
    val normalizedFamily: String? = null,
    val attributes: ExtractedAttributes,
    val candidates: List<RankedCandidate> = emptyList(),
    val missingInformation: List<String> = emptyList(),
    val confidence: ConfidenceLevel,
)

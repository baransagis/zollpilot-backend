package com.zollpilot.domain

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

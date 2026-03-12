package com.zollpilot.domain

import kotlinx.serialization.Serializable

@Serializable
data class FamilyCatalog(
    val families: List<FamilyDefinition>,
)

@Serializable
data class FamilyDefinition(
    val id: String,
    val keywords: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val priority: Int = 0,
)

@Serializable
data class CandidateCatalog(
    val candidates: List<CandidateDefinition>,
)

@Serializable
data class CandidateDefinition(
    val code: String?,
    val label: String,
    val familyMatches: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val materialHints: List<String> = emptyList(),
    val normHints: List<String> = emptyList(),
    val includeTokens: List<String> = emptyList(),
    val excludeTokens: List<String> = emptyList(),
    val dimensionRelevant: Boolean = false,
)

@Serializable
data class CatalogData(
    val families: List<FamilyDefinition>,
    val candidates: List<CandidateDefinition>,
)

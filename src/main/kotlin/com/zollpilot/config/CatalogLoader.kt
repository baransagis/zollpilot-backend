package com.zollpilot.config

import com.zollpilot.domain.CandidateCatalog
import com.zollpilot.domain.CatalogData
import com.zollpilot.domain.FamilyCatalog
import kotlinx.serialization.json.Json

class CatalogLoader(
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    fun load(appConfig: AppConfig, classLoader: ClassLoader = CatalogLoader::class.java.classLoader): CatalogData {
        val families = loadFamilies(appConfig.familiesPath, classLoader)
        val candidates = loadCandidates(appConfig.candidatesPath, classLoader)

        return CatalogData(
            families = families.families,
            candidates = candidates.candidates,
        )
    }

    private fun loadFamilies(path: String, classLoader: ClassLoader): FamilyCatalog {
        val content = loadResource(path, classLoader)
        return json.decodeFromString<FamilyCatalog>(content)
    }

    private fun loadCandidates(path: String, classLoader: ClassLoader): CandidateCatalog {
        val content = loadResource(path, classLoader)
        return json.decodeFromString<CandidateCatalog>(content)
    }

    private fun loadResource(path: String, classLoader: ClassLoader): String {
        val stream = classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource '$path' not found.")

        return stream.bufferedReader().use { it.readText() }
    }
}

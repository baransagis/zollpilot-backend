package com.zollpilot.service

import com.zollpilot.domain.ClassificationBatchResponse
import com.zollpilot.domain.ClassificationResult
import com.zollpilot.domain.LlmJobStatus
import com.zollpilot.domain.LlmJobStatusResponse
import com.zollpilot.domain.LlmProcessingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LlmEnrichmentCoordinator(
    private val classificationService: ClassificationService,
    private val ttl: Duration = Duration.ofMinutes(30),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val jobs = ConcurrentHashMap<String, JobEntry>()

    fun start(localResults: List<ClassificationResult>): ClassificationBatchResponse {
        cleanupExpired()

        if (localResults.isEmpty()) {
            return ClassificationBatchResponse(results = emptyList(), llmJobStatus = LlmJobStatus.SKIPPED)
        }

        val hasPending = localResults.any { it.llmStatus == LlmProcessingStatus.PENDING }
        if (!hasPending) {
            return ClassificationBatchResponse(
                results = localResults,
                llmJobStatus = LlmJobStatus.SKIPPED,
            )
        }

        val jobId = UUID.randomUUID().toString()
        jobs[jobId] = JobEntry(
            llmJobStatus = LlmJobStatus.PROCESSING,
            results = localResults,
            updatedAtMs = System.currentTimeMillis(),
        )

        scope.launch {
            runCatching {
                classificationService.completeLlmEnrichment(localResults)
            }.onSuccess { enriched ->
                jobs[jobId] = JobEntry(
                    llmJobStatus = LlmJobStatus.COMPLETED,
                    results = enriched,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }.onFailure { cause ->
                logger.warn("LLM enrichment job {} failed: {}", jobId, cause.message)
                jobs[jobId] = JobEntry(
                    llmJobStatus = LlmJobStatus.FAILED,
                    results = markFailed(localResults),
                    message = cause.message,
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }

        return ClassificationBatchResponse(
            results = localResults,
            llmJobId = jobId,
            llmJobStatus = LlmJobStatus.PROCESSING,
        )
    }

    fun status(jobId: String): LlmJobStatusResponse? {
        cleanupExpired()
        val entry = jobs[jobId] ?: return null
        return LlmJobStatusResponse(
            llmJobId = jobId,
            llmJobStatus = entry.llmJobStatus,
            results = entry.results,
            message = entry.message,
        )
    }

    private fun markFailed(localResults: List<ClassificationResult>): List<ClassificationResult> {
        return localResults.map { result ->
            if (result.llmStatus == LlmProcessingStatus.PENDING) {
                result.copy(llmStatus = LlmProcessingStatus.FAILED)
            } else {
                result
            }
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val ttlMs = ttl.toMillis()
        jobs.entries.removeIf { (_, entry) ->
            now - entry.updatedAtMs > ttlMs
        }
    }

    private data class JobEntry(
        val llmJobStatus: LlmJobStatus,
        val results: List<ClassificationResult>,
        val message: String? = null,
        val updatedAtMs: Long,
    )
}

package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device Gemma 4 E2B inference via MediaPipe LLM Inference API.
 *
 * Used as tier-1 handler for simple queries to save Opus tokens.
 * Returns ANSWER with response text, or ESCALATE to forward to watch ClawRunner.
 *
 * Escalation rules (always escalate):
 *  - Health/safety/vitals queries
 *  - Multi-turn reasoning
 *  - Tool use requests (web search, calculations)
 *  - Ambiguous or low-confidence results
 */
class PhoneAgent(private val context: Context) {

    companion object {
        private const val TAG = "PhoneAgent"
        // MediaPipe expects .bin model files
        private val MODEL_FILENAMES = listOf(
            "gemma-4-e2b-it.bin",
            "gemma-4-e2b-it.task",
            "gemma-2-2b-it-gpu-int4.bin",
            "gemma3-1b-it-int4.task"
        )

        // Keywords that always escalate to Opus
        private val ESCALATE_KEYWORDS = listOf(
            "health", "heart", "vitals", "blood", "sleep", "steps",
            "exercise", "medical", "doctor", "emergency", "urgent",
            "search", "look up", "find out", "what's happening",
            "analyze", "explain why", "compare", "summarize this",
            "code", "debug", "fix", "implement", "refactor"
        )

        // Patterns that Gemma handles well
        private val SIMPLE_PATTERNS = listOf(
            "hello", "hi ", "hey", "good morning", "good night",
            "what time", "what day", "what date",
            "thank", "thanks", "bye", "goodbye",
            "who are you", "what are you", "what can you do",
            "tell me a joke", "fun fact",
            "define ", "what is a ", "what does",
            "translate", "how do you say"
        )
    }

    sealed class RouterResult {
        data class Answer(val text: String) : RouterResult()
        data class Escalate(val reason: String, val summary: String) : RouterResult()
    }

    // MediaPipe LlmInference instance (typed loosely for graceful degradation)
    private var llmInference: Any? = null
    private var initialized = false
    private var initFailed = false

    /**
     * Classify a query as simple (handle locally) or complex (escalate to Opus).
     * Uses keyword matching first (fast, no inference needed).
     */
    fun classifyQuery(query: String): Boolean {
        val lower = query.lowercase().trim()

        // Always escalate health/safety/tool queries
        if (ESCALATE_KEYWORDS.any { lower.contains(it) }) return false

        // Simple patterns handled locally
        if (SIMPLE_PATTERNS.any { lower.contains(it) }) return true

        // Short queries (< 8 words) that aren't flagged are likely simple
        if (lower.split(" ").size <= 8 && !lower.contains("?")) return true

        // Questions are more likely to need Opus
        if (lower.contains("why") || lower.contains("how") || lower.endsWith("?")) return false

        // Default: escalate to be safe
        return false
    }

    /**
     * Try to answer a query locally with Gemma. Returns RouterResult.
     */
    suspend fun query(prompt: String): RouterResult = withContext(Dispatchers.IO) {
        // First pass: keyword classifier (zero latency)
        if (!classifyQuery(prompt)) {
            return@withContext RouterResult.Escalate(
                reason = "complex_query",
                summary = prompt.take(100)
            )
        }

        // Try Gemma inference
        try {
            val response = runGemmaInference(prompt)
            if (response.isNullOrBlank()) {
                return@withContext RouterResult.Escalate(
                    reason = "empty_response",
                    summary = prompt.take(100)
                )
            }
            RouterResult.Answer(response)
        } catch (e: Exception) {
            Log.e(TAG, "Gemma inference failed: ${e.message}")
            RouterResult.Escalate(
                reason = "inference_error",
                summary = "${prompt.take(80)} | error: ${e.message?.take(50)}"
            )
        }
    }

    /**
     * Initialize MediaPipe LLM Inference with Gemma model.
     * Call once on app start (background thread). Takes ~5-10s.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true
        if (initFailed) return@withContext false

        try {
            val modelFile = findModelFile()
            if (modelFile == null) {
                Log.w(TAG, "Gemma model file not found. PhoneAgent will escalate all queries.")
                Log.w(TAG, "Place a Gemma model file in one of: app files dir, external files dir, /sdcard/Download/")
                initFailed = true
                return@withContext false
            }

            // Use reflection for MediaPipe LLM Inference API
            val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
            val builderClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Builder")
            val llmClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")

            val builder = builderClass.getConstructor().newInstance()
            builderClass.getMethod("setModelPath", String::class.java)
                .invoke(builder, modelFile.absolutePath)
            builderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType)
                .invoke(builder, 256)
            val options = builderClass.getMethod("build").invoke(builder)

            val inference = llmClass.getMethod("createFromOptions", Context::class.java, optionsClass)
                .invoke(null, context, options)

            llmInference = inference
            initialized = true
            Log.i(TAG, "Gemma initialized via MediaPipe from ${modelFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemma: ${e.message}")
            initFailed = true
            false
        }
    }

    private suspend fun runGemmaInference(prompt: String): String? {
        if (!initialized || llmInference == null) {
            if (!initialize()) return null
        }

        val inference = llmInference ?: return null
        return try {
            val response = inference.javaClass
                .getMethod("generateResponse", String::class.java)
                .invoke(inference, prompt) as? String
            response?.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Gemma inference call failed: ${e.message}")
            null
        }
    }

    private fun findModelFile(): File? {
        val candidates = mutableListOf<File>()
        MODEL_FILENAMES.forEach { filename ->
            candidates += File(context.filesDir, filename)
            candidates += File(context.getExternalFilesDir(null), filename)
            candidates += File("/sdcard/Download/$filename")
            candidates += File("/sdcard/Documents/$filename")
            candidates += File("/sdcard/$filename")
        }
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    fun isAvailable(): Boolean = initialized && llmInference != null
}

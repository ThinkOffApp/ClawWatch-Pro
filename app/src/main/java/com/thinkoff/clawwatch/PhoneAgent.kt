package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Gemma 4 E2B inference via Android AICore GenerativeModel.
 *
 * Uses the system-wide Gemma 4 model available on supported devices (S26 Ultra etc.)
 * through the AICore Developer Preview / ML Kit GenAI API.
 *
 * Tier-1 handler for simple queries to save Opus tokens.
 * Returns ANSWER with response text, or ESCALATE to forward to watch ClawRunner.
 *
 * Escalation rules (always escalate):
 *  - Health/safety/vitals queries
 *  - Multi-turn reasoning
 *  - Tool use requests
 *  - Low confidence / ambiguous results
 */
class PhoneAgent(private val context: Context) {

    companion object {
        private const val TAG = "PhoneAgent"

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

    // AICore GenerativeModel instance (reflection-based for graceful degradation)
    private var generativeModel: Any? = null
    private var initialized = false
    private var initFailed = false

    /**
     * Classify a query as simple (handle locally) or complex (escalate to Opus).
     */
    fun classifyQuery(query: String): Boolean {
        val lower = query.lowercase().trim()
        if (ESCALATE_KEYWORDS.any { lower.contains(it) }) return false
        if (SIMPLE_PATTERNS.any { lower.contains(it) }) return true
        if (lower.split(" ").size <= 8 && !lower.contains("?")) return true
        if (lower.contains("why") || lower.contains("how") || lower.endsWith("?")) return false
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
     * Initialize Gemma 4 via AICore GenerativeModel.
     * Prefers E2B (fastest) model. Falls back to MediaPipe if AICore unavailable.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true
        if (initFailed) return@withContext false

        // Try AICore GenerativeModel first
        if (tryInitAiCore()) return@withContext true

        // Fall back to MediaPipe LLM Inference (file-based)
        if (tryInitMediaPipe()) return@withContext true

        Log.w(TAG, "No Gemma runtime available. PhoneAgent will escalate all queries.")
        initFailed = true
        false
    }

    private fun tryInitAiCore(): Boolean {
        return try {
            // AICore GenerativeModel API
            val genModelClass = Class.forName("com.google.ai.edge.aicore.GenerativeModel")
            val configClass = Class.forName("com.google.ai.edge.aicore.GenerationConfig")
            val modelConfigClass = Class.forName("com.google.ai.edge.aicore.ModelConfig")

            // Build config preferring E2B (fast) model
            val modelConfig = modelConfigClass.getConstructor().newInstance()
            // Set preference to COMPACT (E2B = faster, lower memory)
            try {
                val prefEnum = Class.forName("com.google.ai.edge.aicore.ModelPreference")
                val compact = prefEnum.getField("COMPACT").get(null)
                modelConfigClass.getMethod("setPreference", prefEnum).invoke(modelConfig, compact)
            } catch (_: Exception) {
                // Default preference is fine
            }

            val config = configClass.getConstructor().newInstance()
            configClass.getMethod("setModelConfig", modelConfigClass).invoke(config, modelConfig)

            val model = genModelClass.getMethod("getClient", configClass).invoke(null, config)
            generativeModel = model
            initialized = true
            Log.i(TAG, "Gemma 4 initialized via AICore GenerativeModel")
            true
        } catch (e: Exception) {
            Log.d(TAG, "AICore not available: ${e.message}")
            false
        }
    }

    private fun tryInitMediaPipe(): Boolean {
        return try {
            val modelFile = findModelFile() ?: return false
            val optionsBuilderClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions\$Builder"
            )
            val optionsClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions"
            )
            val llmClass = Class.forName(
                "com.google.mediapipe.tasks.genai.llminference.LlmInference"
            )

            val builder = optionsBuilderClass.getConstructor().newInstance()
            optionsBuilderClass.getMethod("setModelPath", String::class.java)
                .invoke(builder, modelFile.absolutePath)
            optionsBuilderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType)
                .invoke(builder, 256)
            val options = optionsBuilderClass.getMethod("build").invoke(builder)

            val inference = llmClass.getMethod("createFromOptions", Context::class.java, optionsClass)
                .invoke(null, context, options)

            generativeModel = inference
            initialized = true
            Log.i(TAG, "Gemma initialized via MediaPipe from ${modelFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.d(TAG, "MediaPipe not available: ${e.message}")
            false
        }
    }

    private suspend fun runGemmaInference(prompt: String): String? {
        if (!initialized || generativeModel == null) {
            if (!initialize()) return null
        }

        val model = generativeModel ?: return null
        return try {
            // Try AICore generateContent first
            val response = try {
                val result = model.javaClass
                    .getMethod("generateContent", String::class.java)
                    .invoke(model, prompt)
                result?.javaClass?.getMethod("getText")?.invoke(result) as? String
            } catch (_: NoSuchMethodException) {
                // Fall back to MediaPipe generateResponse
                model.javaClass
                    .getMethod("generateResponse", String::class.java)
                    .invoke(model, prompt) as? String
            }
            response?.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Gemma inference call failed: ${e.message}")
            null
        }
    }

    private fun findModelFile(): java.io.File? {
        val filenames = listOf(
            "gemma-4-E2B-it.bin", "gemma-4-E4B-it.bin",
            "gemma-4-e2b-it.bin", "gemma-4-e2b-it.task",
            "gemma-2-2b-it-gpu-int4.bin", "gemma3-1b-it-int4.task"
        )
        val dirs = listOf(
            context.filesDir,
            context.getExternalFilesDir(null),
            java.io.File("/sdcard/Download"),
            java.io.File("/sdcard/Documents"),
            java.io.File("/sdcard")
        )
        for (filename in filenames) {
            for (dir in dirs) {
                val f = java.io.File(dir, filename)
                if (f.exists() && f.length() > 0) return f
            }
        }
        return null
    }

    fun isAvailable(): Boolean = initialized && generativeModel != null
}

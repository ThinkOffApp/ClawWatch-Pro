package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device Gemma 4 E2B inference via LiteRT-LM with GPU acceleration.
 * Uses the model file downloaded by Google AI Edge Gallery.
 */
class PhoneAgent(private val context: Context) {

    companion object {
        private const val TAG = "PhoneAgent"
    }

    sealed class RouterResult {
        data class Answer(val text: String) : RouterResult()
        data class Escalate(val reason: String, val summary: String) : RouterResult()
    }

    private var engine: Engine? = null
    private var initialized = false
    private var initFailed = false
    private var modelPath: String? = null

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext true
        if (initFailed) return@withContext false

        val model = findModelFile()
        if (model == null) {
            Log.w(TAG, "No Gemma model found. Install Google AI Edge Gallery and download Gemma 4 E2B.")
            initFailed = true
            return@withContext false
        }
        modelPath = model.absolutePath
        Log.i(TAG, "Found Gemma model: $modelPath (${model.length() / 1024 / 1024}MB)")

        // Try GPU first, fall back to CPU
        for (backend in listOf(Backend.GPU(), Backend.CPU())) {
            try {
                val config = EngineConfig(
                    modelPath = model.absolutePath,
                    backend = backend
                )
                val eng = Engine(config)
                eng.initialize()
                engine = eng
                initialized = true
                val backendName = if (backend is Backend.GPU) "GPU" else "CPU"
                Log.i(TAG, "Gemma initialized with $backendName backend")
                return@withContext true
            } catch (e: Exception) {
                Log.w(TAG, "Backend failed: ${e.message}")
            }
        }

        Log.e(TAG, "All backends failed")
        initFailed = true
        false
    }

    suspend fun query(prompt: String): RouterResult = withContext(Dispatchers.IO) {
        if (!initialized && !initialize()) {
            return@withContext RouterResult.Escalate("not_initialized", "Gemma not available")
        }

        val eng = engine ?: return@withContext RouterResult.Escalate("engine_null", "Engine not ready")

        try {
            runInference(eng, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Gemma inference failed: ${e.message}", e)
            // GPU init can succeed but inference fails (e.g. missing OpenCL)
            // Retry with CPU backend
            if (e.message?.contains("OpenCL") == true || e.message?.contains("GPU") == true) {
                Log.i(TAG, "Retrying with CPU backend...")
                val model = findModelFile() ?: return@withContext RouterResult.Escalate("no_model", "Model gone")
                try {
                    val cpuConfig = EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU())
                    val cpuEng = Engine(cpuConfig)
                    cpuEng.initialize()
                    engine = cpuEng
                    Log.i(TAG, "Reinitialized with CPU backend")
                    runInference(cpuEng, prompt)
                } catch (e2: Exception) {
                    Log.e(TAG, "CPU fallback also failed: ${e2.message}", e2)
                    RouterResult.Escalate("inference_error", e2.message?.take(80) ?: "unknown")
                }
            } else {
                RouterResult.Escalate("inference_error", e.message?.take(80) ?: "unknown")
            }
        }
    }

    private suspend fun runInference(eng: Engine, prompt: String): RouterResult {
        eng.createConversation().use { conversation ->
            val response = StringBuilder()
            conversation.sendMessageAsync(prompt).collect { chunk ->
                response.append(chunk)
            }
            val text = response.toString().trim()
            return if (text.isBlank()) {
                RouterResult.Escalate("empty_response", prompt.take(80))
            } else {
                RouterResult.Answer(text)
            }
        }
    }

    fun isAvailable(): Boolean = initialized && engine != null

    fun getModelInfo(): String =
        if (initialized) "Gemma (${modelPath?.substringAfterLast('/')})" else "Not loaded"

    private fun findModelFile(): File? {
        val candidates = mutableListOf<File>()

        // Edge Gallery models (primary)
        val edgeBase = File("/sdcard/Android/data/com.google.ai.edge.gallery/files")
        if (edgeBase.exists()) {
            edgeBase.walkTopDown()
                .filter { it.name.endsWith(".litertlm") && !it.name.contains("cache") }
                .sortedBy { if (it.path.contains("E2B", ignoreCase = true)) 0 else 1 }
                .forEach { candidates += it }
        }

        // App-local fallbacks
        listOf("gemma-4-e2b-it.litertlm", "gemma-4-e4b-it.litertlm").forEach { name ->
            candidates += File(context.filesDir, name)
            context.getExternalFilesDir(null)?.let { candidates += File(it, name) }
            candidates += File("/sdcard/Download/$name")
        }

        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }
}

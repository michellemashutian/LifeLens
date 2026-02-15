package com.example.lifelens.nexa

import android.content.Context
import android.util.Log
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.VlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.bean.VlmCreateInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

private val manifestJson = Json { ignoreUnknownKeys = true }

class NexaVlmClient(
    private val context: Context,
    private val modelPath: String,
    private val mmprojPath: String? = null,
    private val pluginId: String = "npu"
) {

    private var vlmWrapper: VlmWrapper? = null
    var activePluginId: String = pluginId
        private set

    suspend fun init() = withContext(Dispatchers.IO) {

        // 1) Initialize SDK
        Log.d(TAG, "Initializing NexaSdk...")
        NexaSdk.getInstance().init(context)

        // 2) Validate entry file
        val entryFile = File(modelPath)
        require(entryFile.exists() && entryFile.isFile) {
            "Model entry file not found: ${entryFile.absolutePath}"
        }
        Log.d(TAG, "Entry file OK: ${entryFile.absolutePath} (${entryFile.length()} bytes)")

        // 3) Validate all model files in same directory
        val modelDir = entryFile.parentFile
        if (modelDir != null && modelDir.exists()) {
            val files = modelDir.listFiles() ?: emptyArray()
            Log.d(TAG, "Model dir: ${modelDir.absolutePath} (${files.size} files)")
            files.forEach { f ->
                Log.d(TAG, "  ${f.name}: ${f.length()} bytes")
            }
        }

        // 4) Validate mmproj file if provided
        if (mmprojPath != null) {
            val mmprojFile = File(mmprojPath)
            if (mmprojFile.exists()) {
                Log.d(TAG, "mmproj file OK: ${mmprojFile.absolutePath} (${mmprojFile.length()} bytes)")
            } else {
                Log.w(TAG, "mmproj file NOT found: $mmprojPath")
            }
        } else {
            Log.w(TAG, "No mmproj_path provided — image analysis may not work")
        }

        // 5) Read nexa.manifest for ModelName and effective plugin (align with nexa-sdk-examples)
        val manifest = modelDir?.let { dir ->
            runCatching {
                val f = File(dir, "nexa.manifest")
                if (f.exists() && f.isFile) manifestJson.decodeFromString<NexaManifestBean>(f.bufferedReader().use { it.readText() }) else null
            }.getOrNull()
        }
        val modelName = manifest?.ModelName?.ifBlank { null } ?: "omni-neural"
        val effectivePluginId = manifest?.PluginId ?: pluginId
        Log.d(TAG, "Manifest: modelName=$modelName, effectivePluginId=$effectivePluginId")

        // 6) Load model with config matching nexa-sdk-examples (NPU needs npu_lib_folder_path / npu_model_folder_path)
        val pluginsToTry = listOf(effectivePluginId)

        var lastError: Throwable? = null
        for (pid in pluginsToTry) {
            Log.d(TAG, "Trying to load model with plugin_id=$pid...")
            try {
                val config = if (pid == "npu" && modelDir != null) {
                    ModelConfig(
                        nCtx = 2048,
                        nThreads = 8,
                        enable_thinking = false,
                        npu_lib_folder_path = context.applicationInfo.nativeLibraryDir,
                        npu_model_folder_path = modelDir.absolutePath
                    )
                } else {
                    ModelConfig(
                        nCtx = 1024,
                        nThreads = 4,
                        nBatch = 1,
                        nUBatch = 1,
                        nGpuLayers = 0,
                        enable_thinking = false
                    )
                }

                val input = VlmCreateInput(
                    model_name = modelName,
                    model_path = entryFile.absolutePath,
                    mmproj_path = mmprojPath,
                    config = config,
                    plugin_id = pid,
                    device_id = "HTP0"
                )

                val result = VlmWrapper.builder()
                    .vlmCreateInput(input)
                    .build()

                vlmWrapper = result.getOrThrow()
                activePluginId = pid
                Log.d(TAG, "Model loaded successfully (plugin=$pid)")
                return@withContext
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load with plugin=$pid: ${t.message}", t)
                lastError = t
            }
        }

        // All plugins failed
        throw RuntimeException(
            "VLM init failed on all plugins (tried: ${pluginsToTry.joinToString()}). " +
            "Last error: ${lastError?.message}",
            lastError
        )
    }

    suspend fun generate(prompt: String): String =
        withContext(Dispatchers.IO) {

            val wrapper = vlmWrapper
                ?: error("Model not initialized. Call init() first.")

            val sb = StringBuilder()

            wrapper.generateStreamFlow(
                prompt,
                GenerationConfig()
            ).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> sb.append(result.text)
                    is LlmStreamResult.Completed -> { }
                    is LlmStreamResult.Error ->
                        throw RuntimeException("Generation error: $result")
                }
            }

            sb.toString()
        }

    suspend fun generateWithImage(prompt: String, imagePath: String): String =
        withContext(Dispatchers.IO) {
            val wrapper = vlmWrapper
                ?: error("Model not initialized. Call init() first.")

            val config = GenerationConfig(
                imagePaths = arrayOf(imagePath),
                imageCount = 1
            )

            val sb = StringBuilder()
            wrapper.generateStreamFlow(prompt, config).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> sb.append(result.text)
                    is LlmStreamResult.Completed -> { }
                    is LlmStreamResult.Error ->
                        throw RuntimeException("Generation error: $result")
                }
            }
            sb.toString()
        }

    fun generateWithImageStream(prompt: String, imagePath: String): Flow<String> = flow {
        val wrapper = vlmWrapper
            ?: error("Model not initialized. Call init() first.")

        val config = GenerationConfig(
            imagePaths = arrayOf(imagePath),
            imageCount = 1
        )

        wrapper.generateStreamFlow(prompt, config).collect { result ->
            when (result) {
                is LlmStreamResult.Token -> emit(result.text)
                is LlmStreamResult.Completed -> { }
                is LlmStreamResult.Error ->
                    throw RuntimeException("Generation error: $result")
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun destroy() = withContext(Dispatchers.IO) {
        try { vlmWrapper?.stopStream() } catch (_: Throwable) {}
        try { vlmWrapper?.destroy() } catch (_: Throwable) {}
        vlmWrapper = null
    }

    companion object {
        private const val TAG = "NexaVlmClient"
    }
}

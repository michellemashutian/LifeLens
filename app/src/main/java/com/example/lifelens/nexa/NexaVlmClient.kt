package com.example.lifelens.nexa

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

import com.nexa.sdk.NexaSdk
import com.nexa.sdk.VlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.bean.VlmCreateInput

class NexaVlmClient(
    private val context: Context,
    private val modelPath: String,     // 必须是 files-1-1.nexa 的绝对路径
    private val pluginId: String       // "cpu" or "npu"
) {
    private var vlmWrapper: VlmWrapper? = null
    val activePluginId: String get() = pluginId

    suspend fun init() = withContext(Dispatchers.IO) {
        NexaSdk.getInstance().init(context)

        val entryFile = File(modelPath)
        require(entryFile.exists() && entryFile.isFile && entryFile.length() > 0L) {
            "Model entry file not found: ${entryFile.absolutePath}"
        }

        Log.d(TAG, "Loading model: ${entryFile.absolutePath}, plugin=$pluginId")

        val input = VlmCreateInput(
            model_name = "omni-neural",
            model_path = entryFile.absolutePath,
            config = ModelConfig(max_tokens = 1024, enable_thinking = false),
            plugin_id = pluginId
        )

        vlmWrapper = VlmWrapper.builder()
            .vlmCreateInput(input)
            .build()
            .getOrThrow()

        Log.d(TAG, "Model loaded OK ($pluginId)")
    }

    fun generateWithImageStream(prompt: String, imagePath: String): Flow<String> = flow {
        val wrapper = vlmWrapper ?: error("Model not initialized. Call init() first.")
        val img = File(imagePath)
        require(img.exists() && img.isFile && img.length() > 0L) { "Image not found: $imagePath" }

        val cfg = GenerationConfig(
            imagePaths = arrayOf(img.absolutePath),
            imageCount = 1
        )

        wrapper.generateStreamFlow(prompt, cfg).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> emit(r.text)
                is LlmStreamResult.Completed -> Unit
                is LlmStreamResult.Error -> throw RuntimeException("Generation error: $r")
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun destroy() = withContext(Dispatchers.IO) {
        try { vlmWrapper?.stopStream() } catch (_: Throwable) {}
        try { vlmWrapper?.destroy() } catch (_: Throwable) {}
        vlmWrapper = null
    }

    companion object { private const val TAG = "NexaVlmClient" }
}

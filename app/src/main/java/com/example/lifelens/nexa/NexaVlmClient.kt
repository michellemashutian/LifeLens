package com.example.lifelens.nexa

import android.content.Context
import android.util.Log
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.VlmWrapper
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.bean.VlmChatMessage
import com.nexa.sdk.bean.VlmContent
import com.nexa.sdk.bean.VlmCreateInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

class NexaVlmClient(
    private val context: Context,
    private val modelName: String,
    private val pluginId: String,              // "npu" or "cpu_gpu"
    private val modelFilePath: String,         // .../files-*.nexa
    private val modelDirPath: String,          // .../OmniNeural-4B-mobile
    private val mmprojPath: String? = null,
    private val npuLibFolderPath: String? = null // applicationInfo.nativeLibraryDir
) {
    companion object { private const val TAG = "NexaVlmClient" }

    private var wrapper: VlmWrapper? = null

    /** Accumulated multi-turn chat history, matching the Nexa demo's vlmChatList. */
    private val chatHistory = mutableListOf<VlmChatMessage>()

    fun clearHistory() { chatHistory.clear() }

    /**
     * Load model.
     */
    suspend fun init(
        nCtx: Int = 2048,
        nThreads: Int = 8,
        enableThinking: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            NexaSdk.getInstance().init(context)

            val modelFile = File(modelFilePath)
            require(modelFile.exists() && modelFile.isFile && modelFile.length() > 0L) {
                "modelFile not found/empty: $modelFilePath"
            }

            val modelDir = File(modelDirPath)
            require(modelDir.exists() && modelDir.isDirectory) {
                "modelDir not found/not dir: $modelDirPath"
            }

            Log.i(TAG, "init(): modelName=$modelName pluginId=$pluginId mmprojPath=$mmprojPath")

            mmprojPath?.let { p ->
                val f = File(p)
                require(f.exists() && f.isFile && f.length() > 0L) { "mmproj not found/empty: $p" }
            }

            val config = if (pluginId == "npu") {
                ModelConfig(
                    nCtx = nCtx,
                    nThreads = nThreads,
                    enable_thinking = enableThinking,
                    npu_lib_folder_path = npuLibFolderPath,
                    npu_model_folder_path = modelDirPath
                )
            } else {
                ModelConfig(
                    nCtx = nCtx,
                    nThreads = nThreads,
                    nBatch = 1,
                    nUBatch = 1,
                    enable_thinking = enableThinking
                )
            }

            val input = VlmCreateInput(
                model_name = modelName,
                model_path = modelFilePath,
                mmproj_path = mmprojPath,
                config = config,
                plugin_id = pluginId,
                device_id = "HTP0"
            )

            wrapper = VlmWrapper.builder()
                .vlmCreateInput(input)
                .build()
                .getOrElse { throw it }

            Log.i(TAG, "init(): Model loaded OK")
            Unit
        }
    }

    fun isReady(): Boolean = wrapper != null

    /**
     * Stream tokens for (image + prompt).
     * Accumulates multi-turn chat history like the Nexa demo app.
     * Passes raw input string (not formattedText) to generateStreamFlow,
     * matching the Nexa demo's behavior for NPU models.
     */
    fun generateWithImageStream(imagePath: String, prompt: String): Flow<String> = flow {
        val w = requireNotNull(wrapper) { "VLM not initialized. Call init() first." }

        val img = File(imagePath)
        require(img.exists() && img.isFile && img.length() > 0L) { "Image not found/empty: $imagePath" }

        // Clear previous conversation for fresh single-turn context.
        // NOTE: Do NOT call w.reset() here — it corrupts state before applyChatTemplate
        // and causes error 1037979640. reset() should only be called separately (e.g. clearHistory button).
        chatHistory.clear()

        // Build content list: image first, then text (matching Nexa demo)
        val contents = mutableListOf(
            VlmContent(type = "image", text = img.absolutePath),
            VlmContent(type = "text", text = prompt)
        )

        // Add to accumulated chat history (matching Nexa demo's multi-turn pattern)
        val userMsg = VlmChatMessage(role = "user", contents = contents)
        chatHistory.add(userMsg)

        val allMessages = chatHistory.toTypedArray()

        // 1) Apply chat template
        w.applyChatTemplate(
            messages = allMessages,
            tools = null,
            enableThinking = false
        ).getOrElse { throw it }

        // 2) Inject image paths into config (use GenerationConfigSample for proper maxTokens=2048)
        val baseConfig = GenerationConfigSample().toGenerationConfig()
        val configWithMedia = w.injectMediaPathsToConfig(allMessages, baseConfig)

        Log.i(TAG, "imageCount=${configWithMedia.imageCount} imagePaths=${configWithMedia.imagePaths?.joinToString()}")

        // 3) Stream generation -- pass raw prompt (not formattedText), matching Nexa demo
        val sb = StringBuilder()
        w.generateStreamFlow(prompt, configWithMedia).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> {
                    sb.append(r.text)
                    emit(r.text)
                }
                is LlmStreamResult.Completed -> Log.i(TAG, "completed")
                is LlmStreamResult.Error -> throw r.throwable
            }
        }

        // Add assistant response to chat history for multi-turn
        chatHistory.add(
            VlmChatMessage(
                role = "assistant",
                contents = listOf(VlmContent(type = "text", text = sb.toString()))
            )
        )
    }

    suspend fun reset(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { requireNotNull(wrapper).reset() }
    }

    suspend fun stopStream(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { requireNotNull(wrapper).stopStream().getOrThrow() }
    }

    fun destroy(): Result<Int> = runCatching {
        val w = wrapper
        wrapper = null
        w?.destroy() ?: 0
    }
}

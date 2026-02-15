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
    private val npuLibFolderPath: String? = null // applicationInfo.nativeLibraryDir
) {
    companion object { private const val TAG = "NexaVlmClient" }

    private var wrapper: VlmWrapper? = null

    /**
     * Load model.
     */
    suspend fun init(
        nCtx: Int = 2048,
        nThreads: Int = 8,
        enableThinking: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // ✅ 注意：不同版本 init() 可能返回 Int，但我们不关心返回值
            NexaSdk.getInstance().init(context)

            val modelFile = File(modelFilePath)
            require(modelFile.exists() && modelFile.isFile && modelFile.length() > 0L) {
                "modelFile not found/empty: $modelFilePath"
            }

            val modelDir = File(modelDirPath)
            require(modelDir.exists() && modelDir.isDirectory) {
                "modelDir not found/not dir: $modelDirPath"
            }

            Log.i(TAG, "init(): modelName=$modelName pluginId=$pluginId")
            Log.i(TAG, "init(): modelFile=$modelFilePath (${modelFile.length()} bytes)")
            Log.i(TAG, "init(): modelDir=$modelDirPath")
            Log.i(TAG, "init(): npuLibFolderPath=$npuLibFolderPath")

            val config = if (pluginId == "npu") {
                ModelConfig(
                    nCtx = nCtx,
                    nThreads = nThreads,
                    enable_thinking = enableThinking,
                    npu_lib_folder_path = npuLibFolderPath,
                    npu_model_folder_path = modelDirPath
                    // ✅ device_id 这个字段你当前 SDK 没有，删掉
                )
            } else {
                ModelConfig(
                    nCtx = 1024,
                    nThreads = 4,
                    nBatch = 1,
                    nUBatch = 1,
                    enable_thinking = enableThinking
                )
            }

            val input = VlmCreateInput(
                model_name = modelName,
                model_path = modelFilePath,
                mmproj_path = null,      // ✅ 不需要 mmproj 就保持 null
                config = config,
                plugin_id = pluginId
            )

            wrapper = VlmWrapper.builder()
                .vlmCreateInput(input)
                .build()
                .getOrElse { throw it }

            Log.i(TAG, "init(): Model loaded OK")

            // ✅ 强制让 runCatching 返回 Unit，避免 Result<Int> / Result<Something>
            Unit
        }
    }

    fun isReady(): Boolean = wrapper != null

    /**
     * Stream tokens for (image + prompt).
     */
    fun generateWithImageStream(imagePath: String, prompt: String): Flow<String> = flow {
        val w = requireNotNull(wrapper) { "VLM not initialized. Call init() first." }

        val img = File(imagePath)
        require(img.exists() && img.isFile && img.length() > 0L) { "Image not found/empty: $imagePath" }

        Log.i(TAG, "generateWithImageStream(): plugin=$pluginId img=${img.absolutePath} (${img.length()} bytes)")
        Log.i(TAG, "generateWithImageStream(): prompt=${prompt.take(300)}")

        val messages = arrayOf(
            VlmChatMessage(
                role = "user",
                contents = listOf(
                    VlmContent("image", img.absolutePath),
                    VlmContent("text", prompt)
                )
            )
        )

        val baseConfig = GenerationConfig()
        val configWithMedia = w.injectMediaPathsToConfig(messages, baseConfig)

        w.generateStreamFlow(prompt, configWithMedia).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> emit(r.text)
                is LlmStreamResult.Completed -> Log.i(TAG, "completed")
                is LlmStreamResult.Error -> throw r.throwable
            }
        }
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

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
                plugin_id = pluginId
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
     */
    fun generateWithImageStream(imagePath: String, prompt: String): Flow<String> = flow {
        val w = requireNotNull(wrapper) { "VLM not initialized. Call init() first." }

        val img = File(imagePath)
        require(img.exists() && img.isFile && img.length() > 0L) { "Image not found/empty: $imagePath" }

        // VlmContent(type, text)
        // type: "image" or "text"
        // text: path for image, or the actual text for prompt
        val messages = arrayOf(
            VlmChatMessage(
                role = "user",
                contents = listOf(
                    VlmContent(type = "image", text = img.absolutePath),
                    VlmContent(type = "text", text = prompt)
                )
            )
        )

        // 1) 让 SDK 生成“真正的 prompt” (Chat Template)
        val templateResult = w.applyChatTemplate(
            messages = messages,
            tools = null,
            enableThinking = false
        ).getOrElse { throw it }

        val chatPrompt = templateResult.formattedText

        // 2) 把图片路径注入 config (Inject Media)
        val baseConfig = GenerationConfig()
        val configWithMedia = w.injectMediaPathsToConfig(messages, baseConfig)

        Log.i(TAG, "finalPrompt(head)=${chatPrompt.take(300)}")
        Log.i(TAG, "imageCount=${configWithMedia.imageCount} imagePaths=${configWithMedia.imagePaths}")
        Log.i(
            TAG,
            "imageCount=${configWithMedia.imageCount} imagePaths=${configWithMedia.imagePaths?.joinToString()}"
        )


        // 3) 真正生成 (Stream Flow)
        w.generateStreamFlow(chatPrompt, configWithMedia).collect { r ->
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

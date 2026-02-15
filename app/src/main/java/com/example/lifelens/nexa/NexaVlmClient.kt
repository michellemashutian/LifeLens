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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

class NexaVlmClient(
    private val context: Context,
    private val modelPath: String,   // ✅ 必须是 files-1-1.nexa 的绝对路径
    private val pluginId: String     // ✅ "npu" or "cpu_gpu"
) {

    private var vlmWrapper: VlmWrapper? = null

    val activePluginId: String get() = pluginId

    suspend fun init() = withContext(Dispatchers.IO) {
        val plugin = pluginId.trim().lowercase()
        require(plugin == "npu" || plugin == "cpu_gpu") {
            "Invalid pluginId=$pluginId. Use \"npu\" or \"cpu_gpu\"."
        }

        NexaSdk.getInstance().init(context)

        val entryFile = File(modelPath)
        require(entryFile.exists() && entryFile.isFile && entryFile.length() > 0L) {
            "Model entry file not found: ${entryFile.absolutePath}"
        }

        Log.d(TAG, "init(): entry=${entryFile.absolutePath} len=${entryFile.length()} plugin=$plugin")

        val input = VlmCreateInput(
            model_name = "omni-neural", // ✅ 与 nexa.manifest 一致
            model_path = entryFile.absolutePath,
            config = ModelConfig(
                max_tokens = 512,          // ✅ 先小一点，排除资源问题；跑通后再调大
                enable_thinking = false
            ),
            plugin_id = plugin
        )


        // ✅ build() 返回 Result：失败直接抛异常，外面 Setup 会显示
        vlmWrapper = VlmWrapper.builder()
            .vlmCreateInput(input)
            .build()
            .getOrThrow()

        Log.d(TAG, "init(): Model loaded OK (plugin=$plugin)")
    }

    /**
     * 传入：prompt + imagePath
     * 输出：token stream
     */

    fun generateWithImageStream(prompt: String, imagePath: String): Flow<String> = flow {
        val wrapper = vlmWrapper ?: error("Model not initialized. Call init() first.")
        val img = File(imagePath)
        require(img.exists() && img.isFile && img.length() > 0L) { "Image not found: $imagePath" }

        Log.d(TAG, "generateWithImageStream(): plugin=$pluginId img=${img.absolutePath} (${img.length()} bytes)")

        val cfg = GenerationConfig(
            imagePaths = arrayOf(img.absolutePath),
            imageCount = 1
        )

        wrapper.generateStreamFlow(prompt, cfg).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> emit(r.text)

                is LlmStreamResult.Completed -> {
                    Log.d(TAG, "generateWithImageStream(): Completed")
                }

                is LlmStreamResult.Error -> {
                    val t = r.throwable
                    Log.e(TAG, "generateWithImageStream(): SDK Error object=$r")
                    Log.e(TAG, "generateWithImageStream(): throwable=${t?.javaClass?.name} msg=${t?.message}", t)
                    Log.e(TAG, "generateWithImageStream(): cause=${t?.cause?.javaClass?.name} causeMsg=${t?.cause?.message}", t?.cause)

                    // ✅ 把 message 原样抛出去（里面通常带 error_code）
                    throw RuntimeException("VLM generate failed: ${t?.message ?: r.toString()}", t)
                }
            }
        }
    }.flowOn(Dispatchers.IO)


    suspend fun destroy() = withContext(Dispatchers.IO) {
        Log.d(TAG, "destroy()")
        try { vlmWrapper?.stopStream() } catch (_: Throwable) {}
        try { vlmWrapper?.destroy() } catch (_: Throwable) {}
        vlmWrapper = null
    }

    private fun extractErrorCode(msg: String): Int? {
        // 常见格式：
        // "VLM generate failed, error code: -457230920"
        val regex = Regex("""error\s*code\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
        return regex.find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    companion object {
        private const val TAG = "NexaVlmClient"
    }
}

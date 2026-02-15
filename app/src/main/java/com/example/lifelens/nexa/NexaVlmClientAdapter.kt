package com.example.lifelens.nexa

import com.example.lifelens.tool.VisionClient
import kotlinx.coroutines.flow.collect

class NexaVlmClientAdapter(
    private val client: NexaVlmClient
) : VisionClient {

    override suspend fun explain(imagePath: String, prompt: String): String {
        val sb = StringBuilder()
        client.generateWithImageStream(prompt, imagePath).collect { token ->
            sb.append(token)
        }
        return sb.toString()
    }
}

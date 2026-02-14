package com.example.lifelens.nexa

import com.example.lifelens.tool.VisionClient

class NexaVlmClientAdapter(
    private val client: NexaVlmClient
) : VisionClient {

    override suspend fun explain(imagePath: String, prompt: String): String {
        return client.generateWithImage(prompt, imagePath)
    }
}

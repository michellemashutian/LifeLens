package com.example.lifelens.nexa

import kotlinx.serialization.Serializable

@Serializable
data class NexaManifestBean(
    val ModelName: String? = null,
    val ModelType: String? = null,
    val PluginId: String? = null
)

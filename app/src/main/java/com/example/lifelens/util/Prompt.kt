package com.example.lifelens.util

import com.example.lifelens.tool.Audience

fun defaultQuestion(audience: Audience): String {
    return when (audience) {
        Audience.ELDERLY -> "What is this object?"
        Audience.CHILD -> "What is this?"
    }
}

fun buildPrompt(audience: Audience, userQuestion: String): String {
    return userQuestion.trim().ifEmpty { defaultQuestion(audience) }
}

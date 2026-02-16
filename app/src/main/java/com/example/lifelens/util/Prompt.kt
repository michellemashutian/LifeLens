package com.example.lifelens.util

import com.example.lifelens.tool.Audience

fun defaultQuestion(audience: Audience): String {
    return when (audience) {
        Audience.ELDERLY -> "What is this object?"
        Audience.CHILD -> "What is this?"
    }
}

fun buildPrompt(audience: Audience, userQuestion: String): String {

    val question = userQuestion.trim().ifEmpty { "What is this object?" }

    val core = """
Look at the image.

Focus on the main object in the center.
Ignore the background.

Step 1:
Choose the most likely object category from this list:
(light bulb, bottle, phone, remote, tool, food, clothing, other)

Step 2:
List 3 short visible clues that support your choice.
Only describe clearly visible shapes or parts.
Do NOT mention labels or text unless clearly readable.

Step 3:
Answer the user question.

If unsure, say: "I am not sure."
Do not guess brand or hidden details.
""".trimIndent()

    val style = when (audience) {
        Audience.ELDERLY -> """
Use very simple English.
Use short bullet points.
If relevant, give 2–3 safety tips.
""".trimIndent()

        Audience.CHILD -> """
Use simple friendly words.
Max 5 short sentences.
Include 1 short fun fact.
""".trimIndent()
    }

    return """
$core

$style

User question:
$question
""".trimIndent()
}

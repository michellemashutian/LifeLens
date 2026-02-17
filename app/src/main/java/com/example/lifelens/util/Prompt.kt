package com.example.lifelens.util

import com.example.lifelens.tool.Audience

fun defaultQuestion(audience: Audience): String {
    return when (audience) {
        Audience.ELDERLY -> "What is this object?"
        Audience.CHILD -> "What is this?"
    }
}

fun buildPrompt(audience: Audience, userQuestion: String): String {

    val question = userQuestion.trim().ifEmpty { "What is this object? What is it used for? Any safety concerns?" }

    val shared = """
Look at the image.

Focus on the main object in the center. Ignore the background.

Step 1 (IDENTIFY):
Pick ONE category:
(light bulb, bottle, phone, remote, tool, food, clothing, other)

Step 2 (EVIDENCE):
Write 2–4 short visible clues that support your choice.
Only mention things clearly visible.
Do NOT mention labels or text unless you can clearly read them.

Step 3 (FINAL ANSWER):
Answer the user question based on Step 1 and Step 2.
If you are not sure, say: "I am not sure."
Do not guess brand or model.
""".trimIndent()

    val style = when (audience) {
        Audience.ELDERLY -> """
STYLE:
- Very simple English.
- Short sentences.
- Bullet points.

FINAL ANSWER must include:
- Name of object.
- What it is used for.
- Safety tips (2–4). If none, say: "No special safety concerns."
""".trimIndent()

        Audience.CHILD -> """
STYLE:
- Friendly.
- Simple words.
- Max 6 short sentences.
- Include 1 short fun fact.

FINAL ANSWER must include:
- What it is.
- What it does.
- One safety tip if needed.
""".trimIndent()
    }

    return """
$shared

$style

User question:
$question
""".trimIndent()
}

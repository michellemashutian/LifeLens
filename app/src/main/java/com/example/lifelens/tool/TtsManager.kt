package com.example.lifelens.tool

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = (status == TextToSpeech.SUCCESS)
        if (ready) {
            tts?.language = Locale.SIMPLIFIED_CHINESE
            tts?.setSpeechRate(0.9f) // 慢一点更适合老人
        }
    }

    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lifelens")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

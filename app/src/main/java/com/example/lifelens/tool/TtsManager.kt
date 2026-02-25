package com.example.lifelens.tool

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsManager(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var ready = false
    private var currentRate: Float = 0.45f  // mirrors SpeechSpeed.SLOW default

    override fun onInit(status: Int) {
        ready = (status == TextToSpeech.SUCCESS)
        if (ready) {
            tts?.language = Locale.US
            tts?.setSpeechRate(currentRate)
            tts?.setPitch(0.95f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingChanged(true)
                }
                override fun onDone(utteranceId: String?) {
                    onSpeakingChanged(false)
                }
                @Deprecated("Deprecated in API")
                override fun onError(utteranceId: String?) {
                    onSpeakingChanged(false)
                }
            })
        }
    }

    fun setSpeechRate(rate: Float) {
        currentRate = rate
        tts?.setSpeechRate(rate)
    }

    fun speak(text: String) {
        if (!ready) return
        // Re-apply rate before every speak — some TTS engines silently reset
        // it after stop() or between utterances.
        tts?.setSpeechRate(currentRate)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lifelens_speak")
    }

    fun stop() {
        tts?.stop()
        onSpeakingChanged(false)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        onSpeakingChanged(false)
    }
}

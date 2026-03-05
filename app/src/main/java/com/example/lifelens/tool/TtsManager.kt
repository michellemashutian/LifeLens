package com.example.lifelens.tool

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsManager(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var currentRate: Float = 0.6f   // default = SLOW

    override fun onInit(status: Int) {
        ready = (status == TextToSpeech.SUCCESS)
        if (ready) {
            tts?.language = Locale.US
            tts?.setSpeechRate(currentRate)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    onSpeakingChanged(false)
                }

                @Deprecated("Deprecated in API")
                override fun onError(utteranceId: String?) {
                    Log.w("TtsManager", "TTS error for $utteranceId")
                    onSpeakingChanged(false)
                }
            })
        } else {
            Log.e("TtsManager", "TTS init failed with status $status")
        }
    }

    fun setSpeechRate(rate: Float) {
        currentRate = rate
        tts?.setSpeechRate(rate)
    }

    fun speak(text: String) {
        if (!ready) {
            Log.w("TtsManager", "TTS not ready, ignoring speak()")
            return
        }
        tts?.stop()
        tts?.setSpeechRate(currentRate)
        onSpeakingChanged(true)
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
    }

    fun stop() {
        tts?.stop()
        onSpeakingChanged(false)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val UTTERANCE_ID = "lifelens_tts"
    }
}

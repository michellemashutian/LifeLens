package com.example.lifelens.tool

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

class TtsManager(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var ready = false
    private var currentRate: Float = 0.45f  // mirrors SpeechSpeed.SLOW default
    private var shouldPlay = false
    private var player: MediaPlayer? = null
    private val tempFile = File(appContext.cacheDir, "lifelens_tts.wav")
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onInit(status: Int) {
        ready = (status == TextToSpeech.SUCCESS)
        if (ready) {
            tts?.language = Locale.US
            tts?.setPitch(0.95f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    // Only play if speak() is still active (not stopped by user)
                    if (utteranceId == SYNTH_ID && shouldPlay) {
                        mainHandler.post { playFile() }
                    }
                }

                @Deprecated("Deprecated in API")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { onSpeakingChanged(false) }
                }
            })
        }
    }

    fun setSpeechRate(rate: Float) {
        currentRate = rate
    }

    fun speak(text: String) {
        if (!ready) return
        // Stop anything currently playing or synthesizing
        tts?.stop()
        stopPlayer()
        shouldPlay = true
        onSpeakingChanged(true)
        // Synthesize to file at normal speed; playback speed is controlled by MediaPlayer
        tts?.synthesizeToFile(text, null, tempFile, SYNTH_ID)
    }

    private fun playFile() {
        stopPlayer()
        if (!shouldPlay) return
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { mp ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            // setSpeed controls playback rate independently of TTS engine
                            mp.playbackParams = PlaybackParams()
                                .setSpeed(currentRate)
                                .setPitch(1.0f)
                        }
                    }
                    mp.start()
                }
                setOnCompletionListener {
                    onSpeakingChanged(false)
                    it.release()
                    player = null
                }
                setOnErrorListener { _, _, _ ->
                    onSpeakingChanged(false)
                    true
                }
                prepareAsync()
            }
        }.onFailure { onSpeakingChanged(false) }
    }

    fun stop() {
        shouldPlay = false
        tts?.stop()
        stopPlayer()
        onSpeakingChanged(false)
    }

    private fun stopPlayer() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val SYNTH_ID = "lifelens_synth"
    }
}

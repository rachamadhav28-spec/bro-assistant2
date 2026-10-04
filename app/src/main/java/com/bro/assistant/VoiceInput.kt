package com.bro.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceInput(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onFailed: (String) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null

    val isListening: Boolean
        get() = recognizer != null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (recognizer != null) return
        if (!isAvailable()) {
            onFailed("Speech recognition is not available on this phone. Check that the Google app is enabled.")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (recognizer === r) onReady()
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (recognizer !== r) return
                release()
                onFailed(errorText(error))
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== r) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                release()
                if (text.isEmpty()) onFailed("I didn't catch that.") else onFinalText(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer !== r) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotEmpty()) onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    /** Stop listening and process what was heard so far. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Abort without producing a result. */
    fun cancel() {
        release()
    }

    fun destroy() {
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that. Please try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything."
        SpeechRecognizer.ERROR_AUDIO -> "There was a problem recording audio."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network problem. Speech recognition may need internet."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Try again in a moment."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_SERVER -> "The speech service had an error. Try again."
        else -> "Speech recognition error (code $code)."
    }
}

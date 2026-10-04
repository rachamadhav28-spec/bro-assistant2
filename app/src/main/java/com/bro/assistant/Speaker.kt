package com.bro.assistant

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

data class VoiceOption(val name: String, val label: String)

class Speaker(
    context: Context,
    private val onReadyChanged: (Boolean) -> Unit,
    private val onSpeakingStarted: () -> Unit,
    private val onSpeakingFinished: () -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var currentId = ""
    private var counter = 0

    var isReady = false
        private set

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post {
                if (utteranceId != null && utteranceId == currentId) onSpeakingStarted()
            }
        }

        override fun onDone(utteranceId: String?) {
            finish(utteranceId)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            finish(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            finish(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            finish(utteranceId)
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            main.post { handleInit(status) }
        }
    }

    private fun finish(id: String?) {
        main.post {
            if (id != null && id == currentId) {
                currentId = ""
                onSpeakingFinished()
            }
        }
    }

    private fun handleInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            failInit()
            return
        }
        engine.setOnUtteranceProgressListener(progressListener)
        if (!applyLanguage(engine)) {
            failInit()
            return
        }
        applyVoice()
        isReady = true
        onReadyChanged(true)
    }

    private fun failInit() {
        tts?.shutdown()
        tts = null
        isReady = false
        onReadyChanged(false)
    }

    /** BRO speaks English. Tries English (India) first, then English (US). */
    private fun applyLanguage(engine: TextToSpeech): Boolean {
        var result = engine.setLanguage(Locale("en", "IN"))
        if (result < TextToSpeech.LANG_AVAILABLE) {
            result = engine.setLanguage(Locale.US)
        }
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    private fun availableVoices(): List<Voice> {
        val engine = tts ?: return emptyList()
        return try {
            (engine.voices ?: emptySet<Voice>())
                .filter { it.locale?.language == "en" && isInstalled(it) }
                .sortedBy { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isInstalled(v: Voice): Boolean {
        val features = v.features
        return features == null ||
            !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
    }

    private fun looksMale(v: Voice): Boolean {
        val n = v.name.lowercase(Locale.ROOT)
        return n.contains("male") && !n.contains("female")
    }

    private fun trySetVoice(engine: TextToSpeech, v: Voice): Boolean {
        return try {
            engine.setVoice(v) == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Voice choice order:
     * 1. The voice you picked in the Voice dialog.
     * 2. A voice whose name says "male".
     * 3. The default voice with a deeper pitch.
     */
    private fun applyVoice() {
        val engine = tts ?: return
        if (!applyLanguage(engine)) return // also resets to the default voice
        val all = availableVoices()

        val savedName = prefs.getString(KEY_VOICE, null)
        val saved = if (savedName != null) all.firstOrNull { it.name == savedName } else null
        if (saved != null && trySetVoice(engine, saved)) {
            engine.setPitch(NORMAL_PITCH)
            return
        }

        val male = all.firstOrNull { looksMale(it) }
        if (male != null && trySetVoice(engine, male)) {
            engine.setPitch(NORMAL_PITCH)
            return
        }

        engine.setPitch(DEEP_PITCH)
    }

    fun voiceOptions(): List<VoiceOption> {
        return availableVoices().mapIndexed { index, v ->
            val tag = v.locale?.toLanguageTag() ?: "en"
            val net = if (v.isNetworkConnectionRequired) "online" else "offline"
            val male = if (looksMale(v)) ", male" else ""
            VoiceOption(v.name, "Voice ${index + 1} ($tag, $net$male)")
        }
    }

    fun savedVoiceName(): String? = prefs.getString(KEY_VOICE, null)

    /** Pass null to go back to automatic selection. */
    fun selectVoice(name: String?) {
        val editor = prefs.edit()
        if (name == null) {
            editor.remove(KEY_VOICE)
        } else {
            editor.putString(KEY_VOICE, name)
        }
        editor.apply()
        applyVoice()
    }

    /** Returns true if speech was started. Interrupts anything already being spoken. */
    fun speak(text: String): Boolean {
        val engine = tts
        val clean = text.trim()
        if (!isReady || engine == null || clean.isEmpty()) return false
        val limited = clean.take(TextToSpeech.getMaxSpeechInputLength() - 1)
        counter += 1
        val id = "bro-$counter"
        currentId = id
        val result = engine.speak(limited, TextToSpeech.QUEUE_FLUSH, null, id)
        if (result != TextToSpeech.SUCCESS) {
            currentId = ""
            return false
        }
        return true
    }

    /** Stops speech. Does not trigger the finished callback. */
    fun stop() {
        currentId = ""
        tts?.stop()
    }

    fun shutdown() {
        currentId = ""
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        private const val PREFS_NAME = "bro_prefs"
        private const val KEY_VOICE = "voice_name"
        private const val NORMAL_PITCH = 1.0f
        private const val DEEP_PITCH = 0.8f
    }
}

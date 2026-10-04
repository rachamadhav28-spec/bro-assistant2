package com.bro.assistant

import android.content.Context

class ApiKeyStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("bro_prefs", Context.MODE_PRIVATE)

    fun get(): String = prefs.getString(KEY, "").orEmpty()

    fun has(): Boolean = get().isNotBlank()

    fun save(key: String) {
        prefs.edit().putString(KEY, key.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).remove(OLD_KEY).apply()
    }

    companion object {
        // New name, so an old Anthropic key saved earlier is ignored.
        private const val KEY = "gemini_key"
        private const val OLD_KEY = "ai_key"
    }
}

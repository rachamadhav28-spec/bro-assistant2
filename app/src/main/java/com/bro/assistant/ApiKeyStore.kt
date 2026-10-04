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
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "ai_key"
    }
}

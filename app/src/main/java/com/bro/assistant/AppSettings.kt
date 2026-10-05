package com.bro.assistant

import android.content.Context

/** Small on-phone settings. Right now just: should BRO ask before calling (and later, sending)? */
class AppSettings(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("bro_prefs", Context.MODE_PRIVATE)

    fun askFirst(): Boolean = prefs.getBoolean(KEY_ASK_FIRST, true)

    fun setAskFirst(value: Boolean) {
        prefs.edit().putBoolean(KEY_ASK_FIRST, value).apply()
    }

    companion object {
        private const val KEY_ASK_FIRST = "ask_first"
    }
}

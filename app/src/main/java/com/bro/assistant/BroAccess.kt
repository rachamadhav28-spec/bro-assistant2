package com.bro.assistant

/** What the Accessibility Service can do for BRO. The real service implements this. */
interface AccessDriver {
    /** Presses the Back button. true = Android accepted it. */
    fun back(): Boolean

    /** Presses the Home button. true = Android accepted it. */
    fun home(): Boolean

    /** Asks Android for a screenshot. false = not supported or refused. */
    fun screenshot(): Boolean

    /** Taps the first video in the YouTube results that are on screen. */
    fun clickFirstVideo(): ClickResult
}

enum class ClickResult { CLICKED, NOT_FOUND, WRONG_APP }

/** The running Accessibility Service registers itself here. null = the service is off. */
object BroAccess {
    @Volatile
    var driver: AccessDriver? = null

    val isOn: Boolean
        get() = driver != null
}

/** Decides which on-screen item is a YouTube video result. */
object ResultPicker {
    fun isVideoResult(description: String?): Boolean {
        if (description.isNullOrBlank()) return false
        val d = description.lowercase()
        if (!d.contains("play video")) return false
        if (d.contains("sponsored")) return false
        return true
    }
}

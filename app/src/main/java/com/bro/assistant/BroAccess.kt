package com.bro.assistant

/** One thing visible on the screen, numbered so the AI can pick it. */
data class ScreenItem(
    val id: Int,
    val kind: String,
    val label: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean
)

/** What is on the screen right now: the front app and its visible items, top to bottom. */
data class ScreenSnapshot(val packageName: String, val items: List<ScreenItem>) {

    /** One line per item, for the AI. */
    fun asText(): String {
        if (items.isEmpty()) return "(nothing readable on this screen)"
        val sb = StringBuilder()
        for (item in items) {
            val flags = mutableListOf<String>()
            if (item.clickable) flags.add("click")
            if (item.editable) flags.add("type")
            if (item.scrollable) flags.add("scroll")
            val shown = item.label.replace('"', '\'').replace('\n', ' ').take(80)
            sb.append('[').append(item.id).append("] ").append(item.kind)
            sb.append(" \"").append(shown).append('"')
            if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(",")).append(')')
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** All visible text, used to check that a claimed result really is on the screen. */
    fun plainText(): String = items.joinToString("\n") { it.label }

    /** Changes when the screen changes. Used to notice when BRO is stuck. */
    fun fingerprint(): Int =
        (packageName + items.joinToString("|") { it.kind + it.label }).hashCode()
}

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

    /** Reads what is visible now. null = the screen can't be read (app blocks it, or it is changing). */
    fun snapshot(): ScreenSnapshot?

    /** Taps an item from the latest snapshot. */
    fun clickItem(id: Int): Boolean

    /** Replaces the text of an input field from the latest snapshot. Never used on password fields. */
    fun typeInto(id: Int, text: String): Boolean

    /** Presses Enter / Search on an input field from the latest snapshot (Android 11 and newer). */
    fun submit(id: Int): Boolean

    /** Scrolls the biggest scrollable area on screen. down = true moves to later content. */
    fun scroll(down: Boolean): Boolean
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

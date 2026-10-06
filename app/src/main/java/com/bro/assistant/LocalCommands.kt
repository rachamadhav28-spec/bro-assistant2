package com.bro.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class Command {
    data class OpenApp(val appName: String) : Command()
    data class SearchYouTube(val query: String) : Command()
    data class SearchWeb(val query: String) : Command()
    data class Call(val target: String) : Command()
    data class SetAlarm(val hour: Int, val minute: Int) : Command()
    data class SendMessage(val app: String?, val contact: String, val message: String) : Command()
    data class OpenSettings(val page: String?) : Command()
    object GoHome : Command()
    object GoBack : Command()
    object Screenshot : Command()
    object PlayFirst : Command()
    object TellTime : Command()
    object TellDate : Command()
    object TellDateTime : Command()
    object Greeting : Command()
    object WhoAreYou : Command()
    object Help : Command()
    object Thanks : Command()
}

sealed class ParseResult {
    data class Understood(val command: Command) : ParseResult()
    data class Unclear(val question: String) : ParseResult()
    object NeedsAi : ParseResult()
}

data class Reply(val text: String, val result: BroState)

object LocalCommands {

    private const val NEEDS_AI_TEXT =
        "That is a more complex request, so it needs my AI brain. " +
            "Tap AI and paste your key, or try a simple command like open YouTube."

    // Wake words and polite starters that are removed before understanding.
    private val prefixRegexes = listOf(
        Regex("""^(?:(?:hey|ok|okay|hi) +)?(?:bro|navi)\b[ ,.!:]*""", RegexOption.IGNORE_CASE),
        Regex(
            """^(?:please|now|can you|could you|will you|would you|just|i want to|i wanna|i need to|i would like to|i'd like to|let's|lets|let us)\b[ ,]*""",
            RegexOption.IGNORE_CASE
        )
    )

    // Filler words removed from the end ("what is the time now", "open youtube please").
    private val trailingFiller = Regex(""" (?:right now|please|plz|now|for me|once|quickly|bro|navi)$""")

    // Other ways of saying "open": launch, run, go to, take me to ...
    private val openVerbRegex = Regex("""^(?:open up|launch|run|go to|take me to|switch to|bring up|start up) """)

    private val nonWordRegex = Regex("[^\\p{L}\\p{N}:' ]")
    private val spaceRegex = Regex("\\s+")

    private val sendRegex = Regex(
        """^(?:open (\p{L}+) and )?(?:send|message|text|msg|tell) +(?:a +)?(?:(?:message|text) +)?(?:to +)?(\p{L}[\p{L} ]*?) *(?::\s*|(?:saying|that says|that)\s+)(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val notContacts = setOf("me", "you", "us", "him", "her", "them")

    private val multiStepRegex = Regex(
        """\b(?:then|and then|after that|afterwards|and play|and open|and click|and select|and read|and reply)\b"""
    )

    private val homePhrases = setOf(
        "go home", "home", "go to home", "go to home screen", "go to the home screen",
        "home screen", "open home", "open home screen", "open the home screen",
        "take me home", "main screen", "open main screen"
    )
    private val playFirstPhrases = setOf(
        "play first result", "play the first result", "play first video", "play the first video",
        "play first one", "play the first one", "open first result", "open the first result",
        "open first video", "open the first video", "click first result", "click the first result",
        "click first video", "click the first video", "tap first result", "tap the first result",
        "play top result", "play the top result", "play first", "play the first"
    )
    private val backPhrases = setOf(
        "go back", "back", "press back", "go to previous screen", "previous screen",
        "open previous screen", "open back", "take me back"
    )

    // Time and date questions: every word must be a known, harmless word.
    private val timeVocab = setOf(
        "what", "whats", "what's", "is", "the", "time", "now", "current", "tell", "me", "it",
        "right", "today", "exactly", "say", "show", "give", "my", "phone", "local", "clock",
        "do", "you", "have", "got", "know", "a", "of", "us",
        "kya", "hai", "hua", "abhi", "kitne", "baje", "hain"
    )
    private val dateVocab = setOf(
        "what", "whats", "what's", "is", "the", "date", "today", "today's", "todays", "tell",
        "me", "which", "day", "it", "current", "now", "show", "give", "my", "month", "of",
        "right", "exactly", "say", "us", "a", "phone", "local",
        "aaj", "ki", "tareekh", "tarikh", "kya", "hai"
    )
    private val dateWords = setOf("date", "day", "today", "today's", "todays", "tareekh", "tarikh", "aaj")

    private val greetWords = setOf(
        "hi", "hii", "hiii", "hello", "hey", "hey there", "hi there", "hello there", "yo", "sup",
        "namaste", "good morning", "good afternoon", "good evening", "good night",
        "wake up", "are you there", "are you hearing", "are you hearing me", "you there",
        "can you hear me", "do you hear me", "how are you", "whats up", "what's up"
    )
    private val whoAsk = Regex(
        """^(?:who are you|what is your name|what's your name|whats your name|what are you|tell me about yourself|introduce yourself)$"""
    )
    private val thanksAsk = Regex("""^(?:thanks|thank you|thanks a lot|thank you very much|thanks bro|thx|ok thanks|okay thanks)$""")
    private val helpAsk = Regex("""^(?:help|what can you do|what all can you do|what are your features|how can you help me|what do you do)$""")

    // YouTube search in any word order: "search cats on youtube", "open youtube and search for cats",
    // "cats youtube search", "youtube pe cats search karo" ... The name of the app is removed, then
    // the search words around the topic are trimmed, and what is left is the topic.
    private val ytNameRegex = Regex("""\b(?:youtube|you tube|yt)\b""")
    private val ytVerbs = setOf(
        "search", "find", "look", "lookup", "play", "watch", "show",
        "dhundo", "dhoondo", "khojo"
    )
    private val ytLeading = setOf(
        "open", "start", "and", "search", "find", "look", "lookup", "up", "for", "me", "play",
        "watch", "show", "on", "in", "into", "please", "pls", "pe", "par", "mein", "kar", "karo"
    )
    private val ytTrailing = setOf(
        "on", "in", "at", "for", "pe", "par", "mein", "search", "karo", "kar", "kro", "do",
        "dhundo", "dhoondo", "khojo", "please", "pls", "and"
    )

    private fun youtubeQuery(n: String): String? {
        if (!ytNameRegex.containsMatchIn(n)) return null
        val words = ytNameRegex.replace(n, " ").split(" ").filter { it.isNotEmpty() }
        if (words.none { it in ytVerbs }) return null
        var from = 0
        var to = words.size
        while (from < to && words[from] in ytLeading) from++
        while (to > from && words[to - 1] in ytTrailing) to--
        if (from >= to) return null
        return words.subList(from, to).joinToString(" ")
    }
    private val webSearch = Regex("""^(?:search|google|look up)(?: the web| online| on google| in google)?(?: for)? (.+?)(?: on google| in google| online)?$""")
    private val callRegex = Regex("""^(?:call|phone|dial|ring|make a call to|give a call to|place a call to) (.+)$""")
    private val alarmNoTime = Regex("""^(?:set|create|make|add)(?: me)?(?: an| a)? alarm$""")
    private val alarmMain = Regex("""^(?:set|create|make|add)(?: me)?(?: an| a)? alarm(?: for| at)? (.+)$""")
    private val alarmAlt1 = Regex("""^alarm(?: for| at)? (.+)$""")
    private val alarmAlt2 = Regex("""^wake me(?: up)?(?: at| by)? (.+)$""")
    private val alarmAlt3 = Regex("""^(?:set|create|make|add)(?: me)?(?: an| a)? (.+?) alarm$""")
    private val timeRegex = Regex("""^(\d{1,2})(?:[: ](\d{2}))?\s*(a m|p m|am|pm)?$""")
    private val settingsRegex = Regex(
        """^(?:open )?(?:the )?(?:(wi fi|wifi|bluetooth|display|sound|battery|location|apps|notifications|notification|accessibility) )?settings$"""
    )
    // Words that mean "open X" is really a bigger task ("open instagram search ram").
    private val appTaskWords = setOf("search", "chat", "check", "find", "send", "read", "message", "tell", "post")
    private val openFullRegex = Regex("""^(?:open|start) (?:the )?(.+)$""")
    private val openRegex = Regex("""^(?:open|start) (?:the )?(.+?)(?: app| application)?$""")
    private val openHinglish = Regex("""^(.+?)(?: app)? (?:kholo|khol do|khol de|open karo|open kar do|open kar)$""")
    private val messageNoText = Regex("""^(?:message|text|msg) (.+)$""")

    private val appAliases = mapOf(
        "whatsapp" to "WhatsApp",
        "whats app" to "WhatsApp",
        "what's app" to "WhatsApp",
        "watsapp" to "WhatsApp",
        "wa" to "WhatsApp",
        "youtube" to "YouTube",
        "you tube" to "YouTube",
        "yt" to "YouTube",
        "instagram" to "Instagram",
        "insta" to "Instagram",
        "insta gram" to "Instagram",
        "ig" to "Instagram",
        "gmail" to "Gmail",
        "g mail" to "Gmail",
        "email" to "Gmail",
        "chrome" to "Chrome",
        "google chrome" to "Chrome",
        "maps" to "Google Maps",
        "google maps" to "Google Maps",
        "play store" to "Play Store",
        "playstore" to "Play Store",
        "photos" to "Photos",
        "gallery" to "Gallery",
        "camera" to "Camera",
        "calculator" to "Calculator",
        "clock" to "Clock",
        "contacts" to "Contacts",
        "phone" to "Phone",
        "dialer" to "Phone",
        "messages" to "Messages",
        "sms" to "Messages",
        "telegram" to "Telegram",
        "facebook" to "Facebook",
        "snapchat" to "Snapchat",
        "spotify" to "Spotify",
        "twitter" to "X",
        "x" to "X"
    )

    private fun ok(c: Command): ParseResult = ParseResult.Understood(c)

    private fun titleCase(s: String): String =
        s.split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { ch -> ch.uppercase() } }

    private fun appName(raw: String): String =
        appAliases[raw.trim().lowercase(Locale.ROOT)] ?: titleCase(raw.trim())

    private fun stripPrefixes(input: String): String {
        var t = input
        var changed = true
        while (changed) {
            changed = false
            for (p in prefixRegexes) {
                val m = p.find(t)
                if (m != null && m.value.isNotEmpty()) {
                    t = t.substring(m.value.length)
                    changed = true
                }
            }
        }
        return t
    }

    /** Lowercase, no punctuation (keeps ' and :), wake words and filler removed, "launch" etc. become "open". */
    private fun normalize(raw: String): String {
        var t = raw.lowercase(Locale.ROOT).replace('’', '\'')
        t = nonWordRegex.replace(t, " ")
        t = spaceRegex.replace(t, " ").trim()
        t = stripPrefixes(t)
        var previous: String
        do {
            previous = t
            t = trailingFiller.replace(t, "").trim()
        } while (t != previous)
        t = openVerbRegex.replace(t, "open ")
        return t.trim()
    }

    /** Keeps the original capital letters (for message text), wake words removed. */
    private fun cleanOriginal(raw: String): String {
        val t = stripPrefixes(raw.trim().replace('’', '\''))
        return t.trim().trimEnd('.', '!', '?').trim()
    }

    private fun parseTime(text: String): Pair<Int, Int>? {
        val m = timeRegex.matchEntire(text.trim()) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = if (m.groupValues[2].isEmpty()) 0 else m.groupValues[2].toInt()
        val ampm = m.groupValues[3].replace(" ", "")
        if (minute !in 0..59) return null
        if (ampm.isEmpty()) {
            if (hour !in 0..23) return null
        } else {
            if (hour !in 1..12) return null
            hour = hour % 12 + (if (ampm == "pm") 12 else 0)
        }
        return Pair(hour, minute)
    }

    private fun alarmResult(timeText: String): ParseResult {
        val t = parseTime(timeText)
            ?: return ParseResult.Unclear(
                "I couldn't read that time. Say it like: set an alarm for 7 30 AM."
            )
        return ok(Command.SetAlarm(t.first, t.second))
    }

    fun parse(raw: String): ParseResult {
        val n = normalize(raw)
        if (n.isEmpty()) return ok(Command.Greeting)
        val original = cleanOriginal(raw)
        val words = n.split(" ").filter { it.isNotEmpty() }

        // 1. Message with content (checked first so the message text may contain any words)
        val sm = sendRegex.matchEntire(original)
        if (sm != null) {
            val contact = sm.groupValues[2].trim()
            if (contact.lowercase(Locale.ROOT) !in notContacts) {
                val appRaw = sm.groupValues[1]
                val app = if (appRaw.isEmpty()) null else appName(appRaw)
                return ok(Command.SendMessage(app, titleCase(contact), sm.groupValues[3].trim()))
            }
        }

        // 2. Several steps in one sentence: needs the AI planner
        if (multiStepRegex.containsMatchIn(n)) return ParseResult.NeedsAi

        // 3. Simple phone actions
        if (n in homePhrases) return ok(Command.GoHome)
        if (n in backPhrases) return ok(Command.GoBack)
        if (n.contains("screenshot") || n.contains("screen shot")) {
            // A short "take a screenshot" is for the current screen; longer sentences (open X and ...) go to the planner.
            return if (words.size <= 5 && !n.startsWith("open ")) ok(Command.Screenshot) else ParseResult.NeedsAi
        }
        if (n in playFirstPhrases) return ok(Command.PlayFirst)

        // 4. Time and date questions in many wordings
        val hasTime = words.contains("time") || words.contains("baje")
        val hasDate = words.any { it in dateWords }
        if (hasTime && hasDate && words.all { it in timeVocab || it in dateVocab || it == "and" }) {
            return ok(Command.TellDateTime)
        }
        if (hasTime && words.all { it in timeVocab }) return ok(Command.TellTime)
        if (hasDate && (words.size >= 2 || words.contains("date")) && words.all { it in dateVocab }) {
            return ok(Command.TellDate)
        }

        // 5. Small talk
        if (n in greetWords) return ok(Command.Greeting)
        if (whoAsk.matches(n)) return ok(Command.WhoAreYou)
        if (thanksAsk.matches(n)) return ok(Command.Thanks)
        if (helpAsk.matches(n)) return ok(Command.Help)

        // 6. YouTube search
        val ytQuery = youtubeQuery(n)
        if (ytQuery != null) return ok(Command.SearchYouTube(ytQuery))

        // 7. Web search
        val web = webSearch.matchEntire(n)
        if (web != null) return ok(Command.SearchWeb(web.groupValues[1].trim()))

        // 8. Call
        val call = callRegex.matchEntire(n)
        if (call != null) return ok(Command.Call(titleCase(call.groupValues[1].trim())))

        // 9. Alarm
        if (alarmNoTime.matches(n)) return ParseResult.Unclear("What time should I set the alarm for?")
        val alarm = alarmMain.matchEntire(n)
            ?: alarmAlt2.matchEntire(n)
            ?: alarmAlt3.matchEntire(n)
            ?: alarmAlt1.matchEntire(n)
        if (alarm != null) return alarmResult(alarm.groupValues[1])

        // 10. Settings (before the general "open" rule)
        val st = settingsRegex.matchEntire(n)
        if (st != null) {
            val page = st.groupValues[1]
            val shown = when {
                page.isEmpty() -> null
                page == "wifi" || page == "wi fi" -> "Wi-Fi"
                else -> titleCase(page)
            }
            return ok(Command.OpenSettings(shown))
        }

        // 11. Open an app
        if (n == "open") return ParseResult.Unclear("Which app should I open?")
        val fullName = openFullRegex.matchEntire(n)?.groupValues?.get(1)?.trim()
        if (fullName != null && appAliases.containsKey(fullName)) {
            return ok(Command.OpenApp(appAliases.getValue(fullName)))
        }
        val open = openRegex.matchEntire(n) ?: openHinglish.matchEntire(n)
        if (open != null) {
            val name = open.groupValues[1].trim()
            val nameWords = name.split(" ").filter { it.isNotEmpty() }
            if (name.isEmpty() || name.contains(" and ") || nameWords.size > 3 ||
                nameWords.any { it in appTaskWords }
            ) {
                return ParseResult.NeedsAi
            }
            return ok(Command.OpenApp(appName(name)))
        }

        // 12. "message Rahul" with no text
        val mn = messageNoText.matchEntire(n)
        if (mn != null) {
            return ParseResult.Unclear("What should I tell ${titleCase(mn.groupValues[1].trim())}?")
        }

        return ParseResult.NeedsAi
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val suffix = if (hour < 12) "AM" else "PM"
        return String.format(Locale.US, "%d:%02d %s", h12, minute, suffix)
    }

    private fun describe(cmd: Command): String = when (cmd) {
        is Command.OpenApp -> "open ${cmd.appName}"
        is Command.SearchYouTube -> "search YouTube for ${cmd.query}"
        is Command.SearchWeb -> "search the web for ${cmd.query}"
        is Command.Call -> "call ${cmd.target}"
        is Command.SetAlarm -> "set an alarm for ${formatTime(cmd.hour, cmd.minute)}"
        is Command.SendMessage -> {
            val where = if (cmd.app == null) "" else " on ${cmd.app}"
            "send ${cmd.contact}$where the message: ${cmd.message}"
        }
        is Command.OpenSettings ->
            if (cmd.page == null) "open Settings" else "open ${cmd.page} settings"
        is Command.GoHome -> "go to the home screen"
        is Command.GoBack -> "go back"
        is Command.Screenshot -> "take a screenshot"
        is Command.PlayFirst -> "play the first result"
        else -> "do that"
    }

    private fun reply(cmd: Command): Reply = when (cmd) {
        is Command.TellTime ->
            Reply("It's ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}.", BroState.SUCCESS)

        is Command.TellDate ->
            Reply(
                "Today is ${SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())}.",
                BroState.SUCCESS
            )

        is Command.TellDateTime ->
            Reply(
                "It's ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())} on " +
                    "${SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())}.",
                BroState.SUCCESS
            )

        is Command.Greeting -> Reply("Yes, I'm listening.", BroState.SUCCESS)

        is Command.WhoAreYou ->
            Reply(
                "I'm BRO, your personal assistant. Right now I can listen, talk, and understand your commands.",
                BroState.SUCCESS
            )

        is Command.Thanks -> Reply("You're welcome.", BroState.SUCCESS)

        is Command.Help ->
            Reply(
                "I can open apps, search YouTube or the web, open Settings, set alarms, call your contacts, " +
                    "go to the home screen, and tell you the time and date. " +
                    "With the Accessibility Service switched on, I can also press Back, take a screenshot " +
                    "and play the first YouTube result. With my AI key I can also work inside apps like WhatsApp and Instagram, step by step. " +
                    "For anything more complex, add your AI key under AI.",
                BroState.SUCCESS
            )

        else ->
            Reply(
                "Understood: ${describe(cmd)}. I haven't done it, because actions are added in a later stage.",
                BroState.IDLE
            )
    }

    fun respond(raw: String): Reply = when (val r = parse(raw)) {
        is ParseResult.Understood -> reply(r.command)
        is ParseResult.Unclear -> Reply(r.question, BroState.IDLE)
        is ParseResult.NeedsAi -> Reply(NEEDS_AI_TEXT, BroState.IDLE)
    }
}

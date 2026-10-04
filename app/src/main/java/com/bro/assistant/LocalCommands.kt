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
    object TellTime : Command()
    object TellDate : Command()
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
        "That is a more complex request, so it needs my AI brain, which comes in a later stage. " +
            "For now, try a simple command like open YouTube."

    private val prefixRegexes = listOf(
        Regex("""^(?:(?:hey|ok|okay|hi) +)?(?:bro|navi)\b[ ,.!:]*""", RegexOption.IGNORE_CASE),
        Regex("""^(?:please|now|can you|could you|will you|would you|just)\b[ ,]*""", RegexOption.IGNORE_CASE)
    )

    private val nonWordRegex = Regex("[^\\p{L}\\p{N}:' ]")
    private val spaceRegex = Regex("\\s+")
    private val trailingBro = Regex(""" (?:bro|navi)$""")

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
        "home screen", "open home", "open home screen", "open the home screen"
    )
    private val backPhrases = setOf("go back", "back", "press back", "go to previous screen")

    private val timeAsk = Regex(
        """^(?:what is the time|what's the time|what time is it|tell me the time|current time|time now|time|what is the time now|what time is it now)$"""
    )
    private val dateAsk = Regex(
        """^(?:what is the date|what's the date|today's date|what is today's date|what day is it|what is today|what's today|date|tell me the date)$"""
    )
    private val greetAsk = Regex(
        """^(?:hi|hello|hey|hey there|hi there|hello there|good morning|good afternoon|good evening|wake up|are you there|are you hearing|are you hearing me|you there|can you hear me)$"""
    )
    private val whoAsk = Regex(
        """^(?:who are you|what is your name|what's your name|what are you|tell me about yourself)$"""
    )
    private val thanksAsk = Regex("""^(?:thanks|thank you|thanks a lot|thank you very much)$""")
    private val helpAsk = Regex("""^(?:help|what can you do|what all can you do|what are your features)$""")

    private val ytA = Regex("""^(?:open youtube(?: and)? )?(?:search|find|play) (?:on )?youtube (?:for )?(.+)$""")
    private val ytB = Regex("""^(?:search|find|play) (?:for )?(.+) on youtube$""")
    private val ytC = Regex("""^open youtube(?: and)? search(?: for)? (.+)$""")
    private val webSearch = Regex("""^(?:search|google|look up)(?: the web)?(?: for)? (.+)$""")
    private val callRegex = Regex("""^(?:call|phone|dial) (.+)$""")
    private val alarmRegex = Regex("""^(?:set|create|make)(?: an| a)? alarm(?: for| at)? (.+)$""")
    private val alarmNoTime = Regex("""^(?:set|create|make)(?: an| a)? alarm$""")
    private val timeRegex = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(a m|p m|am|pm)?$""")
    private val settingsRegex = Regex(
        """^open (?:the )?(?:(wi fi|wifi|bluetooth|display|sound|battery|location|apps|notifications|notification|accessibility) )?settings$"""
    )
    private val openRegex = Regex("""^(?:open|launch|start) (?:the )?(.+?)(?: app)?$""")
    private val messageNoText = Regex("""^(?:message|text|msg) (.+)$""")

    private val appAliases = mapOf(
        "whatsapp" to "WhatsApp",
        "whats app" to "WhatsApp",
        "what's app" to "WhatsApp",
        "youtube" to "YouTube",
        "you tube" to "YouTube",
        "instagram" to "Instagram",
        "insta" to "Instagram",
        "gmail" to "Gmail",
        "chrome" to "Chrome"
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

    /** Lowercase, no punctuation (keeps ' and :), wake words removed. */
    private fun normalize(raw: String): String {
        var t = raw.lowercase(Locale.ROOT).replace('’', '\'')
        t = nonWordRegex.replace(t, " ")
        t = spaceRegex.replace(t, " ").trim()
        t = stripPrefixes(t)
        t = trailingBro.replace(t, "")
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

    fun parse(raw: String): ParseResult {
        val n = normalize(raw)
        if (n.isEmpty()) return ok(Command.Greeting)
        val original = cleanOriginal(raw)

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
        if (n.contains("screenshot")) return ok(Command.Screenshot)

        // 4. Questions BRO can answer for real
        if (timeAsk.matches(n)) return ok(Command.TellTime)
        if (dateAsk.matches(n)) return ok(Command.TellDate)
        if (greetAsk.matches(n)) return ok(Command.Greeting)
        if (whoAsk.matches(n)) return ok(Command.WhoAreYou)
        if (thanksAsk.matches(n)) return ok(Command.Thanks)
        if (helpAsk.matches(n)) return ok(Command.Help)

        // 5. YouTube search
        val yt = ytA.matchEntire(n) ?: ytB.matchEntire(n) ?: ytC.matchEntire(n)
        if (yt != null) return ok(Command.SearchYouTube(yt.groupValues[1].trim()))

        // 6. Web search
        val web = webSearch.matchEntire(n)
        if (web != null) return ok(Command.SearchWeb(web.groupValues[1].trim()))

        // 7. Call
        val call = callRegex.matchEntire(n)
        if (call != null) return ok(Command.Call(titleCase(call.groupValues[1].trim())))

        // 8. Alarm
        if (alarmNoTime.matches(n)) return ParseResult.Unclear("What time should I set the alarm for?")
        val alarm = alarmRegex.matchEntire(n)
        if (alarm != null) {
            val t = parseTime(alarm.groupValues[1])
                ?: return ParseResult.Unclear(
                    "I couldn't read that time. Say it like: set an alarm for 7 30 AM."
                )
            return ok(Command.SetAlarm(t.first, t.second))
        }

        // 9. Settings (before the general "open" rule)
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

        // 10. Open an app
        val open = openRegex.matchEntire(n)
        if (open != null) {
            val name = open.groupValues[1].trim()
            if (name.contains(" and ")) return ParseResult.NeedsAi
            return ok(Command.OpenApp(appName(name)))
        }

        // 11. "message Rahul" with no text
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

        is Command.Greeting -> Reply("Yes, I'm listening.", BroState.SUCCESS)

        is Command.WhoAreYou ->
            Reply(
                "I'm BRO, your personal assistant. Right now I can listen, talk, and understand your commands.",
                BroState.SUCCESS
            )

        is Command.Thanks -> Reply("You're welcome.", BroState.SUCCESS)

        is Command.Help ->
            Reply(
                "I understand commands like open YouTube, search YouTube for something, call someone, " +
                    "set an alarm for 7 AM, send a message, go home, go back and take a screenshot. " +
                    "For now I only tell you what I understood, because doing them comes in the next stages. " +
                    "I can already tell you the time and the date.",
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

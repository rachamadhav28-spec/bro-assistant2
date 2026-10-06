package com.bro.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/** Plans that BRO cannot do with one Android intent. They are handed to the AgentRunner. */
val AGENT_TYPES = setOf(
    ActionType.SEND_MESSAGE,
    ActionType.READ_LATEST_MESSAGE,
    ActionType.REPLY_MESSAGE
)

/** Lets the screen's Yes / No answer reach a task that is waiting in the middle of its work. */
class AgentGate {
    @Volatile
    var waiting: Boolean = false
        private set
    private var deferred: CompletableDeferred<Boolean>? = null

    suspend fun ask(): Boolean {
        val d = CompletableDeferred<Boolean>()
        deferred = d
        waiting = true
        try {
            return d.await()
        } finally {
            waiting = false
            deferred = null
        }
    }

    fun answer(yes: Boolean) {
        deferred?.complete(yes)
    }
}

enum class AgentKind { DONE, FAILED, ASKED, DECLINED }

data class AgentOutcome(val kind: AgentKind, val text: String)

/**
 * Works inside apps by looking at the screen, asking the AI for ONE next action, doing it,
 * and looking again. Every action is checked here in code: risky taps need a Yes from the
 * person, and "done" is only accepted when the AI's evidence is really visible on the screen.
 */
class AgentRunner(private val appContext: Context) {

    private val launcher = AppLauncher(appContext)
    private val contacts = ContactResolver(appContext)

    suspend fun run(
        goal: String,
        chat: String,
        apiKey: String,
        askFirst: Boolean,
        onStep: (String) -> Unit,
        confirm: suspend (String) -> Boolean
    ): AgentOutcome {
        val log = mutableListOf<String>()
        var note = ""
        var lastTyped = ""
        var tookScreenshot = false
        var lastPrint = 0
        var sameCount = 0
        var unreadable = 0
        var retries = 0
        var step = 0

        loop@ while (step < MAX_STEPS) {
            val driver = BroAccess.driver
                ?: return AgentOutcome(AgentKind.FAILED, "The Accessibility Service switched off, so I had to stop.")

            val shot = driver.snapshot()
            if (shot == null) {
                unreadable++
                if (unreadable >= MAX_UNREADABLE) {
                    return AgentOutcome(
                        AgentKind.FAILED,
                        "I can't read this screen. Some apps block it, or it kept changing."
                    )
                }
                delay(UNREADABLE_WAIT_MS)
                continue@loop
            }
            unreadable = 0

            val print = shot.fingerprint()
            if (print == lastPrint) sameCount++ else sameCount = 0
            lastPrint = print
            if (sameCount >= MAX_SAME) {
                return AgentOutcome(
                    AgentKind.FAILED,
                    "The screen stopped changing, so I stopped. Last step: ${log.lastOrNull() ?: "none"}."
                )
            }

            val prompt = buildString {
                append("Goal: ").append(goal).append("\n\n")
                if (chat.isNotBlank()) {
                    append("Recent conversation (to understand words like him, her, it):\n")
                    append(chat.takeLast(1200)).append("\n\n")
                }
                append("Actions so far:\n")
                if (log.isEmpty()) {
                    append("(none)\n")
                } else {
                    for (line in log.takeLast(12)) append(line).append('\n')
                }
                if (note.isNotEmpty()) append("\nNote: ").append(note).append('\n')
                append("\nFront app: ").append(shot.packageName).append('\n')
                append("Screen:\n").append(shot.asText())
            }
            note = ""

            val move = when (val reply = AgentClient.nextMove(apiKey, prompt)) {
                is AgentReply.Failure -> {
                    if (reply.retryable && retries < MAX_RETRIES) {
                        retries++
                        delay(RETRY_WAIT_MS)
                        continue@loop
                    }
                    return AgentOutcome(AgentKind.FAILED, reply.message)
                }
                is AgentReply.Move -> reply.move
            }
            retries = 0
            val n = step + 1
            val item = move.id?.let { shot.items.getOrNull(it) }

            when (move.action) {
                "done" -> {
                    val evidence = norm(move.evidence)
                    val onScreen = evidence.length >= MIN_EVIDENCE && norm(shot.plainText()).contains(evidence)
                    if (onScreen) {
                        return AgentOutcome(AgentKind.DONE, move.say.ifBlank { "Task completed." })
                    }
                    if (tookScreenshot) {
                        return AgentOutcome(
                            AgentKind.DONE,
                            "I asked Android for the screenshot. I can't confirm it from here, so please check your Gallery."
                        )
                    }
                    log.add("$n. done -> rejected, the evidence was not on the screen")
                    note = "You said done, but your evidence text is not on the current screen. " +
                        "Copy exact visible text as evidence, or keep working."
                }

                "fail" -> return AgentOutcome(
                    AgentKind.FAILED,
                    move.say.ifBlank { "I couldn't complete that." }
                )

                "ask" -> return AgentOutcome(
                    AgentKind.ASKED,
                    move.say.ifBlank { "I need a bit more information." }
                )

                "open_app" -> {
                    val match = launcher.find(move.app)
                    if (match == null) {
                        log.add("$n. open_app ${move.app} -> not installed")
                        note = "No app called \"${move.app}\" is installed."
                    } else {
                        onStep("Opening ${match.label}")
                        match.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            appContext.startActivity(match.intent)
                            var waited = 0
                            var front = false
                            while (waited < OPEN_WAIT_MS && !front) {
                                delay(OPEN_STEP_MS)
                                waited += OPEN_STEP_MS.toInt()
                                front = driver.snapshot()?.packageName == match.packageName
                            }
                            log.add("$n. open_app ${match.label} -> " + if (front) "it is in front" else "asked, not confirmed in front")
                            delay(SETTLE_MS)
                        } catch (e: ActivityNotFoundException) {
                            log.add("$n. open_app ${match.label} -> could not start")
                            note = "Android could not start that app."
                        } catch (e: SecurityException) {
                            log.add("$n. open_app ${match.label} -> blocked by Android")
                            note = "Android blocked starting that app."
                        }
                    }
                }

                "open_url" -> {
                    val uri = Uri.parse(move.url)
                    val secure = uri.scheme?.equals("https", ignoreCase = true) == true
                    if (!secure || uri.host.isNullOrBlank()) {
                        log.add("$n. open_url -> refused, only https links are allowed")
                        note = "Only https links can be opened."
                    } else {
                        onStep("Opening a link")
                        try {
                            appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            log.add("$n. open_url ${uri.host} -> opened")
                            delay(URL_WAIT_MS)
                        } catch (e: ActivityNotFoundException) {
                            log.add("$n. open_url ${uri.host} -> no app can open it")
                            note = "No app on this phone can open that link."
                        } catch (e: SecurityException) {
                            log.add("$n. open_url ${uri.host} -> blocked by Android")
                            note = "Android blocked that link."
                        }
                    }
                }

                "lookup_contact" -> {
                    onStep("Looking up ${move.contact}")
                    val line = when (val r = contacts.resolve(move.contact)) {
                        is ContactResult.Found -> "Number for ${r.name}: ${r.number}"
                        is ContactResult.Several -> "Several contacts match: ${r.names.joinToString(", ")}. Use ask."
                        is ContactResult.NotFound -> "No contact called \"${move.contact}\" was found."
                        is ContactResult.NoPermission -> "BRO does not have permission to read contacts."
                    }
                    log.add("$n. lookup_contact ${move.contact} -> $line")
                    note = line
                }

                "click" -> {
                    if (item == null) {
                        log.add("$n. click -> no such item on the screen")
                        note = "There is no item with that id on the current screen."
                    } else {
                        val label = item.label
                        if (needsYes(label, askFirst)) {
                            val where = appLabel(shot.packageName)
                            val question = if (label.lowercase(Locale.ROOT).startsWith("send") && lastTyped.isNotBlank()) {
                                "Send \"$lastTyped\" in $where? Tap Yes or say yes."
                            } else {
                                "Tap \"${label.take(40)}\" in $where? Tap Yes or say yes."
                            }
                            if (!confirm(question)) {
                                return AgentOutcome(
                                    AgentKind.DECLINED,
                                    "Okay, I stopped before tapping \"${label.take(40)}\"."
                                )
                            }
                        }
                        onStep("Tapping \"${label.take(40)}\"")
                        val ok = driver.clickItem(item.id)
                        log.add("$n. click [${item.id}] \"${label.take(40)}\" -> " + if (ok) "done" else "did not work")
                        if (!ok) note = "That tap did not work."
                        delay(SETTLE_MS)
                    }
                }

                "type" -> {
                    if (item == null) {
                        log.add("$n. type -> no such item on the screen")
                        note = "There is no item with that id on the current screen."
                    } else if (!item.editable) {
                        log.add("$n. type -> that item is not an input field")
                        note = "That item is not an input field. Tap the field first, or pick another."
                    } else if (item.label.contains("password", ignoreCase = true)) {
                        log.add("$n. type -> refused, password field")
                        note = "I never type into password fields. Use fail if a login is needed."
                    } else if (move.text.isBlank()) {
                        log.add("$n. type -> no text given")
                        note = "No text was given to type."
                    } else {
                        onStep("Typing \"${move.text.take(40)}\"")
                        val ok = driver.typeInto(item.id, move.text)
                        if (ok) lastTyped = move.text
                        log.add("$n. type [${item.id}] \"${move.text.take(40)}\" -> " + if (ok) "typed" else "did not work")
                        if (!ok) note = "Typing did not work in that field."
                        delay(TYPE_SETTLE_MS)
                    }
                }

                "submit" -> {
                    if (item == null || !item.editable) {
                        log.add("$n. submit -> that item is not an input field")
                        note = "That item is not an input field."
                    } else {
                        onStep("Pressing Enter")
                        val ok = driver.submit(item.id)
                        log.add("$n. submit [${item.id}] -> " + if (ok) "done" else "not supported here")
                        if (!ok) note = "Enter did not work. Try tapping the search suggestion or a search button."
                        delay(SETTLE_MS)
                    }
                }

                "scroll_down", "scroll_up" -> {
                    val down = move.action == "scroll_down"
                    onStep("Scrolling")
                    val ok = driver.scroll(down)
                    log.add("$n. ${move.action} -> " + if (ok) "done" else "nothing to scroll")
                    if (!ok) note = "Nothing could be scrolled on this screen."
                    delay(SETTLE_MS)
                }

                "back" -> {
                    onStep("Going back")
                    log.add("$n. back -> " + if (driver.back()) "done" else "refused")
                    delay(SETTLE_MS)
                }

                "home" -> {
                    onStep("Going home")
                    log.add("$n. home -> " + if (driver.home()) "done" else "refused")
                    delay(SETTLE_MS)
                }

                "screenshot" -> {
                    onStep("Taking a screenshot")
                    val ok = driver.screenshot()
                    if (ok) tookScreenshot = true
                    log.add("$n. screenshot -> " + if (ok) "asked Android, it does not confirm" else "refused")
                    if (!ok) note = "Your phone refused the screenshot."
                    delay(SETTLE_MS)
                }

                "wait" -> {
                    log.add("$n. wait")
                    delay(WAIT_MS)
                }

                else -> {
                    log.add("$n. ${move.action} -> not an allowed action")
                    note = "\"${move.action}\" is not an allowed action."
                }
            }
            step++
        }
        return AgentOutcome(
            AgentKind.FAILED,
            "I ran out of steps before finishing. My last step was: ${log.lastOrNull() ?: "none"}."
        )
    }

    private fun needsYes(label: String, askFirst: Boolean): Boolean {
        val l = label.lowercase(Locale.ROOT)
        if (ALWAYS_ASK.containsMatchIn(l)) return true
        return askFirst && ASK_FIRST.containsMatchIn(l)
    }

    private fun appLabel(pkg: String): String {
        return try {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun norm(s: String): String =
        s.lowercase(Locale.ROOT).replace(NON_WORD, " ").replace(SPACES, " ").trim()

    companion object {
        private const val MAX_STEPS = 30
        private const val MAX_SAME = 4
        private const val MAX_UNREADABLE = 6
        private const val MAX_RETRIES = 2
        private const val MIN_EVIDENCE = 3
        private const val SETTLE_MS = 900L
        private const val TYPE_SETTLE_MS = 600L
        private const val WAIT_MS = 1500L
        private const val URL_WAIT_MS = 1800L
        private const val RETRY_WAIT_MS = 6000L
        private const val UNREADABLE_WAIT_MS = 700L
        private const val OPEN_WAIT_MS = 5000
        private const val OPEN_STEP_MS = 500L

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]")
        private val SPACES = Regex("\\s+")

        // Always needs a Yes, even if "ask first" is switched off in Settings.
        private val ALWAYS_ASK = Regex("""\b(?:buy|pay|purchase|order|checkout|delete|remove|block|unsend)\b""")

        // Needs a Yes while "ask first" is on.
        private val ASK_FIRST = Regex("""^(?:send|post|share|publish|reply|call|submit|follow|unfollow|confirm|tweet)\b""")
    }
}

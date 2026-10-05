package com.bro.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/** MainActivity bumps stopCount every time BRO leaves the screen. It is how BRO notices an app opened. */
object ForegroundState {
    @Volatile
    var stopCount: Int = 0
}

data class StepOutcome(
    val action: TaskAction,
    val status: ActionStatus,
    val verification: Verification,
    val message: String,
    val ok: Boolean
)

/** BRO needs a yes before running this plan. The plan inside is already resolved (contact -> number). */
data class Confirmation(val question: String, val plan: ActionPlan)

data class ExecutionOutcome(
    val steps: List<StepOutcome>,
    val text: String,
    val spoken: String,
    val result: BroState,
    val confirmation: Confirmation? = null,
    val permissionsNeeded: List<String> = emptyList()
)

/** Understands "yes" / "no" answers, in English and a little Hinglish. */
object Confirm {
    private val yes = setOf(
        "yes", "yeah", "yep", "yup", "ya", "yea", "sure", "ok", "okay", "confirm", "confirmed",
        "go", "ahead", "proceed", "do", "it", "please", "haan", "han", "ha", "hanji", "correct",
        "right", "definitely", "absolutely", "kar"
    )
    private val no = setOf(
        "no", "nope", "nah", "cancel", "stop", "dont", "don't", "never", "mind", "nevermind",
        "nahi", "nahin", "na", "abort", "wrong", "negative", "not"
    )
    private val ignored = setOf("bro", "navi", "hey", "hi")
    private val clean = Regex("[^\\p{L}\\p{N}' ]")

    /** true = yes, false = no, null = this was not an answer. */
    fun answer(raw: String): Boolean? {
        val words = clean.replace(raw.lowercase(Locale.ROOT).replace('’', '\''), " ")
            .split(" ")
            .filter { it.isNotEmpty() && it !in ignored }
        if (words.isEmpty()) return null
        if (words.any { it in no }) return false
        if (words.all { it in yes }) return true
        return null
    }
}

/**
 * Runs the steps of a checked plan using normal Android features:
 * open app, search YouTube/web, open Settings, go home, set alarm, call.
 * Anything else is reported honestly as "not available yet".
 */
class TaskExecutor(private val context: Context) {

    private val launcher = AppLauncher(context)
    private val contacts = ContactResolver(context)

    private data class Attempt(val intent: Intent, val doneText: String)

    // Steps that put another screen in front of BRO.
    private val launching = setOf(
        ActionType.OPEN_APP,
        ActionType.SEARCH_YOUTUBE,
        ActionType.SEARCH_WEB,
        ActionType.OPEN_SETTINGS,
        ActionType.GO_HOME,
        ActionType.CALL
    )

    private val supported = launching + ActionType.SET_ALARM

    private class Prepared(val plan: ActionPlan, val early: ExecutionOutcome?)

    suspend fun run(plan: ActionPlan, confirmed: Boolean = false, askFirst: Boolean = true): ExecutionOutcome {
        val prepared = prepare(plan, confirmed, askFirst)
        val early = prepared.early
        if (early != null) return early

        val actions = prepared.plan.actions
        val outcomes = mutableListOf<StepOutcome>()
        var launched = false
        var stopped = false

        for ((i, a) in actions.withIndex()) {
            if (stopped) {
                outcomes.add(StepOutcome(a, ActionStatus.SKIPPED, Verification.NOT_CHECKED, "Not run.", false))
                continue
            }

            // "Open YouTube" followed by "search YouTube" is one launch: the search opens the app.
            val next = actions.getOrNull(i + 1)
            if (a.type == ActionType.OPEN_APP &&
                isYouTube(a.params["app"]) &&
                next?.type == ActionType.SEARCH_YOUTUBE
            ) {
                outcomes.add(
                    StepOutcome(
                        a, ActionStatus.SKIPPED, Verification.NOT_CHECKED,
                        "Covered by the next step, because the search opens YouTube.", true
                    )
                )
                continue
            }

            if (a.type == ActionType.SET_ALARM) {
                val outcome = setAlarm(a)
                outcomes.add(outcome)
                if (!outcome.ok) stopped = true
                continue
            }

            if (a.type in launching) {
                if (launched) {
                    outcomes.add(
                        StepOutcome(
                            a, ActionStatus.SKIPPED, Verification.NOT_CHECKED,
                            "Not run. Android does not reliably let me start a second app right " +
                                "after the first one opens. Ask me for this as a separate command.",
                            false
                        )
                    )
                    stopped = true
                    continue
                }
                val outcome = launch(a)
                outcomes.add(outcome)
                if (outcome.ok) launched = true else stopped = true
            } else {
                outcomes.add(
                    StepOutcome(a, ActionStatus.SKIPPED, Verification.NOT_CHECKED, notAvailable(a), false)
                )
                stopped = true
            }
        }
        return summarize(outcomes)
    }

    // ---------- before running: contacts, permissions, confirmation ----------

    private fun early(text: String, spoken: String, result: BroState, confirmation: Confirmation? = null,
                      permissions: List<String> = emptyList()): Prepared =
        Prepared(ActionPlan(emptyList()), ExecutionOutcome(emptyList(), text, spoken, result, confirmation, permissions))

    private fun prepare(plan: ActionPlan, confirmed: Boolean, askFirst: Boolean): Prepared {
        val actions = plan.actions.toMutableList()
        var callIndex = -1

        for ((i, a) in plan.actions.withIndex()) {
            if (a.type !in supported) break
            if (a.type != ActionType.CALL) continue
            if (callIndex < 0) callIndex = i
            if (!a.params["number"].isNullOrBlank()) continue

            val who = a.params["contact"].orEmpty()
            when (val r = contacts.resolve(who)) {
                is ContactResult.NoPermission -> return early(
                    "To find $who I need permission to read your contacts. Please allow it on the next screen.",
                    "I need permission to read your contacts.",
                    BroState.IDLE,
                    permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
                )
                is ContactResult.NotFound -> return early(
                    "I couldn't find $who in your contacts. You can also say the number, like: call 98765 43210.",
                    "I couldn't find $who in your contacts.",
                    BroState.ERROR
                )
                is ContactResult.Several -> return early(
                    "I found several contacts: ${r.names.joinToString(", ")}. Say call and the full name.",
                    "I found several contacts. Please say the full name.",
                    BroState.IDLE
                )
                is ContactResult.Found -> {
                    actions[i] = a.copy(params = a.params + mapOf("name" to r.name, "number" to r.number))
                }
            }
        }

        val resolved = ActionPlan(actions)
        if (callIndex >= 0 && !confirmed && askFirst) {
            val call = resolved.actions[callIndex]
            val name = call.params["name"].orEmpty()
            val number = call.params["number"].orEmpty()
            val onlyStep = resolved.actions.size == 1
            val question = if (onlyStep) {
                if (sameNumber(name, number)) "Call $number? Tap Yes or say yes."
                else "Call $name on $number? Tap Yes or say yes."
            } else {
                "Here's what I'll do:\n" +
                    resolved.actions.joinToString("\n") { it.line() } +
                    "\nShall I go ahead? Tap Yes or say yes."
            }
            val spoken = if (onlyStep) "Shall I call $name?" else "Shall I go ahead with this plan?"
            return early(question, spoken, BroState.IDLE, confirmation = Confirmation(question, resolved))
        }
        return Prepared(resolved, null)
    }

    // ---------- running steps ----------

    private fun sameNumber(a: String, b: String): Boolean =
        a.filter { it.isDigit() } == b.filter { it.isDigit() }

    private fun isYouTube(app: String?): Boolean =
        app != null && app.lowercase().filter { it.isLetterOrDigit() } == "youtube"

    private fun describe(a: TaskAction): String = a.line().substringAfter(". ")

    private fun notAvailable(a: TaskAction): String =
        if (a.type.method == Method.ACCESSIBILITY) {
            "I understood: ${describe(a)}. That needs the Accessibility Service, which comes in Stages 11 to 14."
        } else {
            "I understood: ${describe(a)}. That isn't available yet."
        }

    private fun fail(a: TaskAction, message: String): StepOutcome =
        StepOutcome(a, ActionStatus.FAILED, Verification.FAILED, message, false)

    private suspend fun launch(a: TaskAction): StepOutcome {
        return when (a.type) {
            ActionType.OPEN_APP -> {
                val name = a.params["app"].orEmpty()
                val match = launcher.find(name)
                    ?: return fail(a, "I couldn't find an app called $name on this phone.")
                start(a, listOf(Attempt(match.intent, "Opened ${match.label}.")), "open ${match.label}")
            }

            ActionType.SEARCH_YOUTUBE -> {
                val query = a.params["query"].orEmpty()
                val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
                val done = "Searched YouTube for $query."
                val attempts = mutableListOf<Attempt>()
                if (launcher.isInstalled(YOUTUBE_PACKAGE)) {
                    attempts.add(Attempt(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(YOUTUBE_PACKAGE), done))
                }
                attempts.add(Attempt(Intent(Intent.ACTION_VIEW, Uri.parse(url)), done))
                start(a, attempts, "search YouTube for $query")
            }

            ActionType.SEARCH_WEB -> {
                val query = a.params["query"].orEmpty()
                val done = "Searched the web for $query."
                val attempts = listOf(
                    Attempt(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query), done),
                    Attempt(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
                        ),
                        done
                    )
                )
                start(a, attempts, "search the web for $query")
            }

            ActionType.OPEN_SETTINGS -> {
                val page = a.params["page"].orEmpty()
                val target = settingsAction(page)
                val done = if (page.isBlank()) {
                    "Opened Settings."
                } else if (target.second) {
                    "Opened $page settings."
                } else {
                    "Opened Settings. I can't jump straight to the $page page."
                }
                start(a, listOf(Attempt(Intent(target.first), done)), "open Settings")
            }

            ActionType.GO_HOME -> {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                start(a, listOf(Attempt(home, "Went to the home screen.")), "go to the home screen")
            }

            ActionType.CALL -> {
                val number = a.params["number"].orEmpty()
                if (number.isBlank()) return fail(a, "I don't have a number to call.")
                val rawName = a.params["name"].orEmpty()
                val byNumber = rawName.isBlank() || sameNumber(rawName, number)
                val name = if (byNumber) number else rawName
                val tel = Uri.fromParts("tel", number, null)
                // Very short numbers (like emergency numbers) are never dialed automatically.
                val canCallDirectly = number.count { it.isDigit() } > 3 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                    PackageManager.PERMISSION_GRANTED
                val attempts = mutableListOf<Attempt>()
                if (canCallDirectly) {
                    attempts.add(Attempt(Intent(Intent.ACTION_CALL, tel), "Calling $name."))
                }
                val dialerText = if (byNumber) {
                    "I opened the dialer with $number. Tap the call button to ring."
                } else {
                    "I opened the dialer with $name's number. Tap the call button to ring."
                }
                attempts.add(Attempt(Intent(Intent.ACTION_DIAL, tel), dialerText))
                start(a, attempts, "call $name")
            }

            else -> fail(a, "That step is not available yet.")
        }
    }

    /** Returns the settings screen action, and whether it is the exact page that was asked for. */
    private fun settingsAction(page: String): Pair<String, Boolean> {
        val p = page.lowercase().replace("-", " ")
        return when {
            p.isBlank() -> Pair(Settings.ACTION_SETTINGS, true)
            p.contains("wi") && p.contains("fi") -> Pair(Settings.ACTION_WIFI_SETTINGS, true)
            p.contains("bluetooth") -> Pair(Settings.ACTION_BLUETOOTH_SETTINGS, true)
            p.contains("display") || p.contains("brightness") -> Pair(Settings.ACTION_DISPLAY_SETTINGS, true)
            p.contains("sound") || p.contains("volume") -> Pair(Settings.ACTION_SOUND_SETTINGS, true)
            p.contains("battery") -> Pair(Settings.ACTION_BATTERY_SAVER_SETTINGS, true)
            p.contains("location") -> Pair(Settings.ACTION_LOCATION_SOURCE_SETTINGS, true)
            p.contains("accessib") -> Pair(Settings.ACTION_ACCESSIBILITY_SETTINGS, true)
            p.contains("app") -> Pair(Settings.ACTION_APPLICATION_SETTINGS, true)
            else -> Pair(Settings.ACTION_SETTINGS, false)
        }
    }

    /**
     * Tries each attempt in order. Afterwards it watches for BRO leaving the screen, which is
     * the only evidence available at this point that another screen really came up.
     */
    private suspend fun start(a: TaskAction, attempts: List<Attempt>, tryText: String): StepOutcome {
        val before = ForegroundState.stopCount
        var used: Attempt? = null
        for (attempt in attempts) {
            try {
                attempt.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(attempt.intent)
                used = attempt
                break
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // try the next one
            }
        }
        if (used == null) {
            return fail(a, "Nothing on this phone could $tryText.")
        }

        var waited = 0
        while (waited < VERIFY_WAIT_MS) {
            if (ForegroundState.stopCount > before) {
                return StepOutcome(a, ActionStatus.SUCCESS, Verification.VERIFIED, used.doneText, true)
            }
            delay(VERIFY_STEP_MS)
            waited += VERIFY_STEP_MS.toInt()
        }
        if (ForegroundState.stopCount > before) {
            return StepOutcome(a, ActionStatus.SUCCESS, Verification.VERIFIED, used.doneText, true)
        }
        return StepOutcome(
            a, ActionStatus.SUCCESS, Verification.UNVERIFIABLE,
            "I asked Android to $tryText, but I couldn't confirm that it came up.", true
        )
    }

    // ---------- alarm ----------

    private fun timeLabel(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return String.format(Locale.US, "%d:%02d %s", h12, minute, if (hour < 12) "AM" else "PM")
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private suspend fun setAlarm(a: TaskAction): StepOutcome {
        val hour = a.params["hour"]?.toIntOrNull()
        val minute = a.params["minute"]?.toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            return fail(a, "That alarm time isn't valid.")
        }
        val label = timeLabel(hour, minute)
        val expected = nextOccurrence(hour, minute)
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "BRO")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return fail(a, "No clock app on this phone can set alarms.")
        } catch (e: SecurityException) {
            return fail(a, "Android blocked setting the alarm.")
        }

        // The phone reports its next alarm, so BRO can check that the alarm really exists.
        var waited = 0
        while (waited < ALARM_WAIT_MS) {
            if (alarmMatches(alarmManager, expected)) {
                return StepOutcome(a, ActionStatus.SUCCESS, Verification.VERIFIED, "Alarm set for $label.", true)
            }
            delay(ALARM_STEP_MS)
            waited += ALARM_STEP_MS.toInt()
        }
        if (alarmMatches(alarmManager, expected)) {
            return StepOutcome(a, ActionStatus.SUCCESS, Verification.VERIFIED, "Alarm set for $label.", true)
        }
        return StepOutcome(
            a, ActionStatus.SUCCESS, Verification.UNVERIFIABLE,
            "I asked the Clock app to set an alarm for $label, but I couldn't confirm it. " +
                "Please check your Clock app.",
            true
        )
    }

    private fun alarmMatches(alarmManager: AlarmManager?, expected: Long): Boolean {
        val next = alarmManager?.nextAlarmClock?.triggerTime ?: return false
        return abs(next - expected) <= 60_000L
    }

    // ---------- summary ----------

    private fun summarize(outcomes: List<StepOutcome>): ExecutionOutcome {
        val anyFailed = outcomes.any { it.status == ActionStatus.FAILED }
        val allOk = outcomes.all { it.ok }
        val unconfirmed = outcomes.any { it.verification == Verification.UNVERIFIABLE }
        val result = when {
            allOk && !unconfirmed -> BroState.SUCCESS
            anyFailed -> BroState.ERROR
            else -> BroState.IDLE
        }

        if (outcomes.size == 1) {
            val m = outcomes[0].message
            val spoken = if (outcomes[0].ok || anyFailed) m else "I understood that, but I can't do it yet."
            return ExecutionOutcom(outcomes, m, spoken, result)
        }
 
        val header = when {
            allOk && !unconfirmed -> "Done."
            allOk -> "Done, but I couldn't confirm every step."
            anyFailed -> "Something went wrong."
            else -> "Partly done."
        }
        val lines = outcomes.joinToString("\n") { it.action.line() + ": " + it.message }
        val spoken = when {
            allOk && !unconfirmed -> "Done."
            allOk -> "Done, but I couldn't confirm every step."
            anyFailed -> outcomes.first { it.status == ActionStatus.FAILED }.message
            else -> "I did the first part, but not the rest."
        }
        return ExecutionOutcome(outcomes, header + "\n" + lines, spoken, result)
    }
 
    companion object {
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val VERIFY_WAIT_MS = 5000
        private const val VERIFY_STEP_MS = 150L
        private const val ALARM_WAIT_MS = 4000
        private const val ALARM_STEP_MS = 300L
    }
}
 

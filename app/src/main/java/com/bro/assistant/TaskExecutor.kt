package com.bro.assistant

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.net.URLEncoder
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

data class ExecutionOutcome(
    val steps: List<StepOutcome>,
    val text: String,
    val spoken: String,
    val result: BroState
)

/**
 * Stage 9: runs the steps of a checked plan that only need normal Android intents
 * (open app, search YouTube, search the web, open Settings).
 * Everything else is reported honestly as "not available yet".
 */
class TaskExecutor(private val context: Context) {

    private val launcher = AppLauncher(context)

    private val launching = setOf(
        ActionType.OPEN_APP,
        ActionType.SEARCH_YOUTUBE,
        ActionType.SEARCH_WEB,
        ActionType.OPEN_SETTINGS
    )

    suspend fun run(plan: ActionPlan): ExecutionOutcome {
        val actions = plan.actions
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

    private fun isYouTube(app: String?): Boolean =
        app != null && app.lowercase().filter { it.isLetterOrDigit() } == "youtube"

    private fun describe(a: TaskAction): String = a.line().substringAfter(". ")

    private fun notAvailable(a: TaskAction): String =
        if (a.type.method == Method.ACCESSIBILITY) {
            "I understood: ${describe(a)}. That needs the Accessibility Service, which comes in Stages 11 to 14."
        } else {
            "I understood: ${describe(a)}. Doing that comes in Stage 10."
        }

    private fun fail(a: TaskAction, message: String): StepOutcome =
        StepOutcome(a, ActionStatus.FAILED, Verification.FAILED, message, false)

    private suspend fun launch(a: TaskAction): StepOutcome {
        return when (a.type) {
            ActionType.OPEN_APP -> {
                val name = a.params["app"].orEmpty()
                val match = launcher.find(name)
                    ?: return fail(a, "I couldn't find an app called $name on this phone.")
                start(a, listOf(match.intent), "Opened ${match.label}.", "open ${match.label}")
            }

            ActionType.SEARCH_YOUTUBE -> {
                val query = a.params["query"].orEmpty()
                val url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
                val intents = mutableListOf<Intent>()
                if (launcher.isInstalled(YOUTUBE_PACKAGE)) {
                    intents.add(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(YOUTUBE_PACKAGE))
                }
                intents.add(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                start(a, intents, "Searched YouTube for $query.", "search YouTube for $query")
            }

            ActionType.SEARCH_WEB -> {
                val query = a.params["query"].orEmpty()
                val intents = listOf(
                    Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query),
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
                    )
                )
                start(a, intents, "Searched the web for $query.", "search the web for $query")
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
                start(a, listOf(Intent(target.first)), done, "open Settings")
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
     * Tries each intent in order. Afterwards it watches for BRO leaving the screen, which is
     * the only evidence available at this stage that another app really opened.
     */
    private suspend fun start(
        a: TaskAction,
        intents: List<Intent>,
        doneText: String,
        tryText: String
    ): StepOutcome {
        val before = ForegroundState.stopCount
        var started = false
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                started = true
                break
            } catch (e: ActivityNotFoundException) {
                // try the next intent
            } catch (e: SecurityException) {
                // try the next intent
            }
        }
        if (!started) {
            return fail(a, "Nothing on this phone could $tryText.")
        }

        var waited = 0
        while (waited < VERIFY_WAIT_MS) {
            if (ForegroundState.stopCount > before) {
                return StepOutcome(a, ActionStatus.SUCCESS, Verification.VERIFIED, doneText, true)
            }
            delay(VERIFY_STEP_MS)
            waited += VERIFY_STEP_MS.toInt()
        }
        if (ForegroundState.stopCount > before) {
            return StepOutcome(a, ActionStatus.SUCCESS, Verification.VERIFIED, doneText, true)
        }
        return StepOutcome(
            a, ActionStatus.SUCCESS, Verification.UNVERIFIABLE,
            "I asked Android to $tryText, but I couldn't confirm that it came up.", true
        )
    }

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
            return ExecutionOutcome(outcomes, m, spoken, result)
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
    }
}

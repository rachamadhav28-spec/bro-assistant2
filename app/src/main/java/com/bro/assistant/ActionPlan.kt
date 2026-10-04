package com.bro.assistant

import java.util.Locale

/** How an action can be carried out on Android. */
enum class Method(val label: String) {
    INTENT("Android intent"),
    ACCESSIBILITY("Accessibility Service")
}

enum class ActionStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

enum class Verification { NOT_CHECKED, VERIFIED, UNVERIFIABLE, FAILED }

/** Every action BRO is allowed to plan. Anything else is rejected. */
enum class ActionType(
    val id: String,
    val title: String,
    val method: Method,
    val required: List<String>,
    val optional: List<String> = emptyList(),
    val consequential: Boolean = false
) {
    OPEN_APP("open_app", "Open app", Method.INTENT, listOf("app")),
    SEARCH_YOUTUBE("search_youtube", "Search YouTube", Method.INTENT, listOf("query")),
    SEARCH_WEB("search_web", "Search the web", Method.INTENT, listOf("query")),
    PLAY_FIRST_RESULT("play_first_result", "Play first result", Method.ACCESSIBILITY, emptyList()),
    SEND_MESSAGE(
        "send_message", "Send message", Method.ACCESSIBILITY,
        listOf("contact", "message"), listOf("app"), true
    ),
    READ_LATEST_MESSAGE("read_latest_message", "Read latest message", Method.ACCESSIBILITY, listOf("app")),
    REPLY_MESSAGE(
        "reply_message", "Reply to message", Method.ACCESSIBILITY,
        listOf("message"), emptyList(), true
    ),
    CALL("call", "Call", Method.INTENT, listOf("contact"), emptyList(), true),
    SET_ALARM("set_alarm", "Set alarm", Method.INTENT, listOf("hour", "minute")),
    OPEN_SETTINGS("open_settings", "Open settings", Method.INTENT, emptyList(), listOf("page")),
    GO_HOME("go_home", "Go to home screen", Method.INTENT, emptyList()),
    GO_BACK("go_back", "Go back", Method.ACCESSIBILITY, emptyList()),
    SCREENSHOT("screenshot", "Take screenshot", Method.ACCESSIBILITY, emptyList());

    companion object {
        fun fromId(id: String): ActionType? {
            val wanted = id.trim().lowercase(Locale.ROOT)
            return values().firstOrNull { it.id == wanted }
        }
    }
}

/** One step of a plan, with everything the task engine will need to track it. */
data class TaskAction(
    val index: Int,
    val type: ActionType,
    val params: Map<String, String>,
    val status: ActionStatus = ActionStatus.PENDING,
    val verification: Verification = Verification.NOT_CHECKED,
    val error: String? = null,
    val maxRetries: Int = 1
) {
    val needsConfirmation: Boolean
        get() = type.consequential

    fun line(): String {
        val details = if (type == ActionType.SET_ALARM) {
            val h = params["hour"]?.toIntOrNull() ?: 0
            val m = params["minute"]?.toIntOrNull() ?: 0
            val h12 = if (h % 12 == 0) 12 else h % 12
            String.format(Locale.US, "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
        } else {
            params.entries
                .filter { it.value.isNotBlank() }
                .joinToString(", ") { it.key + ": " + it.value }
        }
        val tail = if (details.isEmpty()) "" else " ($details)"
        return "$index. ${type.title}$tail"
    }
}

data class ActionPlan(val actions: List<TaskAction>) {
    val needsConfirmation: Boolean
        get() = actions.any { it.needsConfirmation }

    val needsAccessibility: Boolean
        get() = actions.any { it.type.method == Method.ACCESSIBILITY }

    fun summary(): String {
        val sb = StringBuilder()
        for (a in actions) sb.append(a.line()).append('\n')
        if (needsAccessibility) {
            sb.append("Some steps need the Accessibility Service, which is not set up yet.\n")
        }
        if (needsConfirmation) {
            sb.append("I will ask before sending or calling.\n")
        }
        sb.append("Checked and ready, but not run yet. Running starts in the next stages.")
        return sb.toString()
    }
}

sealed class PlanCheck {
    data class Valid(val plan: ActionPlan) : PlanCheck()
    data class Invalid(val problems: List<String>) : PlanCheck()
}

object ActionPlanner {
    private const val MAX_STEPS = 10

    /** Checks an AI plan against the allowed actions and their required fields. */
    fun fromAi(steps: List<AiStep>): PlanCheck {
        if (steps.isEmpty()) return PlanCheck.Invalid(listOf("The plan has no steps."))
        if (steps.size > MAX_STEPS) {
            return PlanCheck.Invalid(listOf("The plan has too many steps (more than $MAX_STEPS)."))
        }
        val problems = mutableListOf<String>()
        val actions = mutableListOf<TaskAction>()
        for ((i, step) in steps.withIndex()) {
            val n = i + 1
            val type = ActionType.fromId(step.action)
            if (type == null) {
                problems.add("Step $n: \"${step.action}\" is not an action I can do.")
                continue
            }
            val params = step.params
                .filterKeys { it in type.required || it in type.optional }
                .mapValues { it.value.trim() }
            for (field in type.required) {
                if (params[field].isNullOrBlank()) {
                    problems.add("Step $n (${type.title}): missing $field.")
                }
            }
            if (type == ActionType.SET_ALARM) {
                val h = params["hour"]?.toIntOrNull()
                val m = params["minute"]?.toIntOrNull()
                if (h == null || h !in 0..23) problems.add("Step $n (${type.title}): hour must be 0 to 23.")
                if (m == null || m !in 0..59) problems.add("Step $n (${type.title}): minute must be 0 to 59.")
            }
            actions.add(TaskAction(index = n, type = type, params = params))
        }
        return if (problems.isEmpty()) PlanCheck.Valid(ActionPlan(actions))
        else PlanCheck.Invalid(problems)
    }

    /** Turns a locally understood command into a plan. Returns null for plain answers (time, date, greetings). */
    fun fromCommand(cmd: Command): ActionPlan? {
        val action: Pair<ActionType, Map<String, String>>? = when (cmd) {
            is Command.OpenApp -> Pair(ActionType.OPEN_APP, mapOf("app" to cmd.appName))
            is Command.SearchYouTube -> Pair(ActionType.SEARCH_YOUTUBE, mapOf("query" to cmd.query))
            is Command.SearchWeb -> Pair(ActionType.SEARCH_WEB, mapOf("query" to cmd.query))
            is Command.Call -> Pair(ActionType.CALL, mapOf("contact" to cmd.target))
            is Command.SetAlarm -> Pair(
                ActionType.SET_ALARM,
                mapOf("hour" to cmd.hour.toString(), "minute" to cmd.minute.toString())
            )
            is Command.SendMessage -> {
                val p = linkedMapOf("contact" to cmd.contact, "message" to cmd.message)
                if (cmd.app != null) p["app"] = cmd.app
                Pair(ActionType.SEND_MESSAGE, p)
            }
            is Command.OpenSettings -> Pair(
                ActionType.OPEN_SETTINGS,
                if (cmd.page == null) emptyMap<String, String>() else mapOf("page" to cmd.page)
            )
            is Command.GoHome -> Pair(ActionType.GO_HOME, emptyMap<String, String>())
            is Command.GoBack -> Pair(ActionType.GO_BACK, emptyMap<String, String>())
            is Command.Screenshot -> Pair(ActionType.SCREENSHOT, emptyMap<String, String>())
            else -> null
        }
        if (action == null) return null
        return ActionPlan(listOf(TaskAction(index = 1, type = action.first, params = action.second)))
    }
}

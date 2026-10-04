package com.bro.assistant

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class AiStep(val action: String, val params: Map<String, String>)

sealed class AiResult {
    data class Plan(val kind: String, val reply: String, val steps: List<AiStep>) : AiResult()
    data class Failure(val message: String) : AiResult()
}

object AiClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val API_VERSION = "2023-06-01"

    private val SYSTEM_PROMPT = """
You are the planning brain of BRO, a personal assistant on an Android phone.
Reply with ONE JSON object only. No markdown, no code fences, no text outside the JSON.

Schema:
{"type":"chat|plan|clarify","reply":"short plain text","steps":[{"action":"...", ...fields}]}

Types:
- "chat": the user is just talking or asking a question. Answer in reply. steps must be [].
- "clarify": information is missing. Ask one short question in reply. steps must be [].
- "plan": the user wants phone actions. List the steps in order. reply is a short sentence such as "Working on it."

Allowed actions and their fields (use only these):
- open_app {"app"}
- search_youtube {"query"}
- play_first_result {}
- send_message {"app","contact","message"}
- read_latest_message {"app"}
- reply_message {"message"}
- call {"contact"}
- set_alarm {"hour","minute"}  (hour is 0-23, minute is 0-59)
- open_settings {"page"}
- go_home {}
- go_back {}
- screenshot {}

Rules:
- Use the conversation to resolve words like him, her, it, there and "the same message" into real names and text.
- Never invent a contact name or message text. If it is unclear, use type "clarify".
- If the request cannot be done with the allowed actions, use type "chat" and say so briefly.
- Never say a task is finished. You only plan.
- Keep reply under 25 words. It will be spoken aloud, so no symbols or lists.

Example request: Open YouTube, search cats and play the first result
Example answer: {"type":"plan","reply":"Working on it.","steps":[{"action":"open_app","app":"YouTube"},{"action":"search_youtube","query":"cats"},{"action":"play_first_result"}]}
""".trimIndent()

    /** history = list of (role, text) where role is "user" or "assistant". Must start and end with "user". */
    suspend fun ask(apiKey: String, history: List<Pair<String, String>>): AiResult {
        if (history.isEmpty()) return AiResult.Failure("There was nothing to send to the AI.")
        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject()
                body.put("model", MODEL)
                body.put("max_tokens", 600)
                body.put("system", SYSTEM_PROMPT)
                val msgs = JSONArray()
                for ((role, text) in history) {
                    msgs.put(JSONObject().put("role", role).put("content", text))
                }
                body.put("messages", msgs)

                conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-api-key", apiKey)
                conn.setRequestProperty("anthropic-version", API_VERSION)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code in 200..299) {
                    parseResponse(text)
                } else {
                    AiResult.Failure(errorMessage(code, text))
                }
            } catch (e: IOException) {
                AiResult.Failure("I couldn't reach the AI. Check your internet connection.")
            } catch (e: JSONException) {
                AiResult.Failure("I couldn't read the AI's answer.")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: JSONException) {
            ""
        }
        val base = when (code) {
            401 -> "The AI key was rejected. Open AI and paste the key again."
            403 -> "The AI key does not have permission."
            429 -> "The AI is busy or the rate limit was reached. Try again in a moment."
            else -> "The AI returned an error (code $code)."
        }
        return if (detail.isNotEmpty() && code != 401 && code != 429) "$base $detail" else base
    }

    private fun parseResponse(raw: String): AiResult {
        val root = JSONObject(raw)
        val content = root.optJSONArray("content") ?: return AiResult.Failure("The AI sent an empty answer.")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return parsePlan(sb.toString())
    }

    private fun parsePlan(text: String): AiResult {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return AiResult.Failure("I couldn't understand the AI's answer.")
        }
        val obj = try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: JSONException) {
            return AiResult.Failure("I couldn't understand the AI's answer.")
        }
        val kind = obj.optString("type", "chat").lowercase()
        val reply = obj.optString("reply", "").trim()
        val steps = mutableListOf<AiStep>()
        val arr = obj.optJSONArray("steps")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val action = s.optString("action", "").trim()
                if (action.isEmpty()) continue
                val params = linkedMapOf<String, String>()
                val keys = s.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != "action") params[k] = s.optString(k)
                }
                steps.add(AiStep(action, params))
            }
        }
        return AiResult.Plan(kind, reply, steps)
    }
}

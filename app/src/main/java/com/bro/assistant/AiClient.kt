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
    // To change the model later, change only this line.
    private const val MODEL = "gemini-3.5-flash-lite"
    private const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

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
                body.put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                    )
                )
                val contents = JSONArray()
                for ((role, text) in history) {
                    val geminiRole = if (role == "assistant") "model" else "user"
                    contents.put(
                        JSONObject()
                            .put("role", geminiRole)
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                }
                body.put("contents", contents)
                body.put(
                    "generationConfig",
                    JSONObject()
                        .put("responseMimeType", "application/json")
                        .put("maxOutputTokens", 800)
                )

                val url = URL(ENDPOINT_BASE + MODEL + ":generateContent")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey)
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
        val keyProblem = detail.contains("API key", ignoreCase = true)
        return when {
            code == 401 || (code == 400 && keyProblem) ->
                "The AI key was rejected. Tap AI and paste a fresh Gemini key."
            code == 403 ->
                "The AI key does not have permission. Create a new key in Google AI Studio."
            code == 404 ->
                "The AI model name was not found. The model may have been renamed."
            code == 429 ->
                "The free AI limit was reached. Wait a minute and try again."
            else ->
                "The AI returned an error (code $code)." +
                    (if (detail.isNotEmpty()) " $detail" else "")
        }
    }

    private fun parseResponse(raw: String): AiResult {
        val root = JSONObject(raw)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return AiResult.Failure("The AI did not give an answer. It may have been blocked.")
        }
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return AiResult.Failure("The AI sent an empty answer.")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            sb.append(p.optString("text"))
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

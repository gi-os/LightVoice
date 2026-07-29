package com.gios.lightvoice.net

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ToolCall(val name: String, val args: JSONObject)

/**
 * What the model decided: some number of actions to run, and/or something to say.
 * A plan with no calls is a plain answer to a plain question.
 */
data class Plan(val calls: List<ToolCall>, val text: String)

/**
 * Turns a sentence into actions, using Claude Haiku's tool use rather than
 * hand-rolled regex.
 *
 * One round trip, always. The model chooses tools; the confirmation you hear is
 * composed locally by the dispatcher from what actually happened, so a scheduled
 * alarm is never described by a model that hasn't seen the result. Only when no tool
 * fits does the model's own text become the answer.
 */
object Brain {
    private const val URL = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5-20251001"

    fun plan(utterance: String, contactNames: List<String>, apiKey: String): Plan {
        if (apiKey.isBlank()) throw VoiceError("No Anthropic key yet — add one in Settings.")

        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 700)
            .put("system", systemPrompt(contactNames))
            .put("tools", tools())
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", utterance),
                ),
            )
            .toString()

        val req = Request.Builder()
            .url(URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        Http.client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw VoiceError(Stt.explain(resp.code, raw, "Claude"))
            val content = runCatching { JSONObject(raw).optJSONArray("content") }.getOrNull()
                ?: throw VoiceError("Claude sent something I couldn't read.")

            val calls = ArrayList<ToolCall>()
            val said = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "tool_use" -> calls.add(
                        ToolCall(
                            block.optString("name"),
                            block.optJSONObject("input") ?: JSONObject(),
                        ),
                    )
                    "text" -> said.append(block.optString("text"))
                }
            }
            return Plan(calls, said.toString().trim())
        }
    }

    private fun systemPrompt(contactNames: List<String>): String {
        val now = LocalDateTime.now()
        val stamp = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))
        val people = if (contactNames.isEmpty()) {
            "(the address book is empty or unread — if asked to call or text someone, " +
                "say you have no contacts yet)"
        } else {
            contactNames.joinToString(", ")
        }
        return """
            You are the voice assistant on a Light Phone III. You take one spoken
            sentence, transcribed by Whisper, and either run a tool or answer.

            Right now it is $stamp, timezone ${ZoneId.systemDefault().id}. Use this for
            anything relative: "in ten minutes", "tomorrow at seven", "tonight".

            The user's contacts: $people

            Rules:
            - Prefer a tool over talking. "Wake me at seven" is set_alarm, not an answer.
            - Times are 24-hour "HH:MM". Resolve am/pm the way a person would: "seven"
              in the evening means 19:00, "wake me at seven" means 07:00.
            - When calling or texting, pass the contact name exactly as spelled in the
              list above, correcting whatever the transcript guessed.
            - For send_message, write the message as the user dictated it, cleaned up
              into normal punctuation. Never add a greeting or a sign-off of your own.
            - The transcript may be garbled. Pick the most plausible reading rather than
              asking for a repeat, unless it is truly unintelligible.
            - If no tool fits, answer in at most two short sentences. This is read aloud
              and shown on a 3.9-inch screen, so no lists, no markdown, no preamble.
        """.trimIndent()
    }

    private fun tool(name: String, description: String, properties: JSONObject, required: List<String>) =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "input_schema",
                JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", JSONArray().also { arr -> required.forEach { arr.put(it) } }),
            )

    private fun prop(type: String, description: String) =
        JSONObject().put("type", type).put("description", description)

    private fun arrayProp(description: String) = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", JSONObject().put("type", "string"))

    private fun tools(): JSONArray = JSONArray()
        .put(
            tool(
                "set_alarm",
                "Schedule an alarm at a wall-clock time. Use for waking up and for any " +
                    "recurring time of day.",
                JSONObject()
                    .put("time", prop("string", "24-hour time, \"HH:MM\"."))
                    .put("label", prop("string", "Short reason, if the user gave one. May be omitted."))
                    .put(
                        "repeat",
                        arrayProp(
                            "Days it repeats: any of mon,tue,wed,thu,fri,sat,sun, or the " +
                                "single word weekdays, weekends or daily. Omit for a one-off.",
                        ),
                    ),
                listOf("time"),
            ),
        )
        .put(
            tool(
                "set_timer",
                "Start a countdown. Use whenever the user says a duration rather than a time.",
                JSONObject()
                    .put("seconds", prop("integer", "Total length of the countdown in seconds."))
                    .put("label", prop("string", "What it is for, e.g. pasta. May be omitted.")),
                listOf("seconds"),
            ),
        )
        .put(
            tool(
                "set_reminder",
                "Ring at a moment with a message to show. Use for \"remind me to X at/in Y\".",
                JSONObject()
                    .put("text", prop("string", "What to remind them of, phrased as the reminder."))
                    .put("time", prop("string", "24-hour \"HH:MM\" if an absolute time was given."))
                    .put("in_seconds", prop("integer", "Seconds from now, if a delay was given instead.")),
                listOf("text"),
            ),
        )
        .put(
            tool(
                "cancel_alarms",
                "Cancel scheduled alarms, timers or reminders.",
                JSONObject()
                    .put(
                        "which",
                        prop("string", "\"all\", or words matching the label or time to cancel."),
                    ),
                listOf("which"),
            ),
        )
        .put(
            tool(
                "list_alarms",
                "Read back what is currently scheduled.",
                JSONObject(),
                emptyList(),
            ),
        )
        .put(
            tool(
                "call_contact",
                "Place a phone call to someone in the contact list.",
                JSONObject().put("name", prop("string", "Contact name, exactly as listed.")),
                listOf("name"),
            ),
        )
        .put(
            tool(
                "send_message",
                "Send an iMessage to someone in the contact list.",
                JSONObject()
                    .put("name", prop("string", "Contact name, exactly as listed."))
                    .put("text", prop("string", "The message body, as dictated.")),
                listOf("name", "text"),
            ),
        )
        .put(
            tool(
                "add_note",
                "Save a note for later. Use for \"note that\", \"remember\", \"add to my list\".",
                JSONObject().put("text", prop("string", "The note, tidied into a sentence.")),
                listOf("text"),
            ),
        )
        .put(
            tool(
                "list_notes",
                "Read back the saved notes.",
                JSONObject(),
                emptyList(),
            ),
        )
}

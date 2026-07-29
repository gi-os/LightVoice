package com.gios.lightvoice.act

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * The two BlueBubbles Server calls this app needs, in the same plain
 * `HttpURLConnection` idiom LightChat uses. Auth is the server password as a query
 * param; the base URL is the user's own server, normally reached over Tailscale.
 *
 * Sending goes through `POST /api/v1/chat/new` rather than `message/text`: for a
 * single address AppleScript's "send to buddy" lands in the existing thread if there
 * is one and starts a new one if there isn't, so the assistant never has to resolve
 * a chat guid first. That keeps texting to one round trip and avoids depending on
 * the server's Private API being live.
 */
object Bb {

    fun sendText(baseUrl: String, password: String, address: String, text: String): String {
        val body = JSONObject()
            .put("addresses", JSONArray().put(address))
            .put("message", text)
            .put("service", "iMessage")
            .put("method", "apple-script")
        val resp = post(baseUrl, "/api/v1/chat/new", password, body)
        return JSONObject(resp).optJSONObject("data")?.optString("guid").orEmpty()
    }

    fun reachable(baseUrl: String, password: String): Boolean = runCatching {
        get(baseUrl, "/api/v1/server/info", password, null).isNotBlank()
    }.getOrDefault(false)

    /** The Mac's address book flattened to (address, name) pairs. */
    fun contacts(baseUrl: String, password: String): List<Pair<String, String>> {
        val text = get(baseUrl, "/api/v1/contact", password, null)
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val name = entry.str("displayName").ifBlank {
                listOf(entry.str("firstName"), entry.str("lastName"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            if (name.isBlank()) continue
            for (field in listOf("phoneNumbers", "emails")) {
                val arr = entry.optJSONArray(field) ?: continue
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.optString("address")?.takeIf { it.isNotBlank() }
                        ?.let { out.add(it to name) }
                }
            }
        }
        return out
    }

    /** org.json turns an explicit JSON null into the literal string "null", which
     *  would otherwise render as somebody's name. */
    private fun JSONObject.str(key: String): String =
        if (isNull(key)) "" else optString(key, "").trim()

    private fun post(base: String, path: String, password: String, body: JSONObject) =
        request("POST", base, path, password, body)

    private fun get(base: String, path: String, password: String, body: JSONObject?) =
        request("GET", base, path, password, body)

    private fun request(
        method: String,
        base: String,
        path: String,
        password: String,
        body: JSONObject?,
    ): String {
        val url = base.trimEnd('/') + path + "?password=" + URLEncoder.encode(password, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException(
                    if (code == 401 || code == 403) "BlueBubbles rejected the password"
                    else "BlueBubbles $path failed ($code)",
                )
            }
            text
        } finally {
            conn.disconnect()
        }
    }
}

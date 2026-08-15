package com.sketchdxf.app.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Forced-update gate: on boot, fetches a tiny JSON config hosted right in this repo (no backend
 * needed — shipping a new minimum version is just a commit to update-config.json on `main`). If
 * the installed build's versionCode is below minVersionCode, BootScreen routes to a blocking
 * "update required" screen instead of the app, using the message/updateUrl from the same config.
 *
 * update-config.json shape: { "minVersionCode": 0, "message": "...", "updateUrl": "..." }
 *
 * Network failures (offline, DNS, timeout, malformed JSON) fail OPEN — [fetch] returns null and
 * the app boots normally — so a hiccup fetching this can never brick the app for someone who's
 * simply offline; only an explicit, successfully-fetched higher minVersionCode blocks anything.
 */
object UpdateChecker {
    private const val CONFIG_URL = "https://raw.githubusercontent.com/shiyaska1/autocad/main/update-config.json"
    private const val TIMEOUT_MS = 4000

    data class Result(val minVersionCode: Int, val message: String, val updateUrl: String)

    /** Blocking network call — always run from a background dispatcher. */
    fun fetch(): Result? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            Result(
                minVersionCode = json.optInt("minVersionCode", 0),
                message = json.optString("message", "A new version is available. Please update to continue."),
                updateUrl = json.optString("updateUrl", License.BUY_URL)
            )
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

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
 *
 * [fetchCached] is what actually runs on every launch: it only hits the network once per
 * [CACHE_TTL_MS] window, serving the last-fetched result straight from [AppPrefs] the rest of the
 * time ("one time load, then save in app, don't load again from net"). A short TTL rather than a
 * true one-shot-forever cache, since a pure one-shot would defeat the whole point of a forced-
 * update gate — the app would never learn about a newly-published bad build after its first ever
 * launch.
 */
object UpdateChecker {
    private const val CONFIG_URL = "https://raw.githubusercontent.com/shiyaska1/autocad/main/update-config.json"
    private const val TIMEOUT_MS = 4000
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24h

    data class Result(val minVersionCode: Int, val message: String, val updateUrl: String)

    /** Cache-first: reuses the last fetched config until it's older than [CACHE_TTL_MS], only
     *  then making a real network call. Still run from a background dispatcher — a cache miss
     *  falls through to the same blocking HTTP request as [fetch]. */
    fun fetchCached(prefs: AppPrefs): Result? {
        val age = System.currentTimeMillis() - prefs.updateCheckedAt
        if (prefs.updateCheckedAt > 0L && age < CACHE_TTL_MS) {
            return Result(prefs.cachedMinVersionCode, prefs.cachedUpdateMessage, prefs.cachedUpdateUrl)
        }
        val fresh = fetch()
        if (fresh != null) {
            prefs.updateCheckedAt = System.currentTimeMillis()
            prefs.cachedMinVersionCode = fresh.minVersionCode
            prefs.cachedUpdateMessage = fresh.message
            prefs.cachedUpdateUrl = fresh.updateUrl
        }
        return fresh
    }

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

package com.sketchdxf.app.data

import android.content.Context

/** Small SharedPreferences wrapper for install date + license/trial state. */
class AppPrefs(context: Context) {
    private val p = context.getSharedPreferences("sketchdxf_prefs", Context.MODE_PRIVATE)

    var installDateMillis: Long
        get() = p.getLong("install_date", 0L)
        set(v) { p.edit().putLong("install_date", v).apply() }

    /**
     * The highest renewal milestone (months) activated so far. Staying below the currently
     * due milestone (see [License.dueMilestone]) is what triggers the license screen again —
     * so a renewal at month 6 doesn't get re-asked at month 1's old milestone.
     */
    var licensedMilestone: Int
        get() {
            val stored = p.getInt("licensed_milestone", 0)
            return if (stored == 0 && p.getBoolean("licensed", false)) 1 else stored
        }
        set(v) { p.edit().putInt("licensed_milestone", v).apply() }

    var licensed: Boolean
        get() = p.getBoolean("licensed", false)
        set(v) { p.edit().putBoolean("licensed", v).apply() }

    /** Wall-clock time (millis) of the last successful forced-update config fetch, or 0 if
     *  never fetched. [UpdateChecker] uses this to avoid hitting the network on every launch. */
    var updateCheckedAt: Long
        get() = p.getLong("update_checked_at", 0L)
        set(v) { p.edit().putLong("update_checked_at", v).apply() }

    var cachedMinVersionCode: Int
        get() = p.getInt("update_min_version_code", 0)
        set(v) { p.edit().putInt("update_min_version_code", v).apply() }

    var cachedUpdateMessage: String
        get() = p.getString("update_message", "") ?: ""
        set(v) { p.edit().putString("update_message", v).apply() }

    var cachedUpdateUrl: String
        get() = p.getString("update_url", "") ?: ""
        set(v) { p.edit().putString("update_url", v).apply() }
}

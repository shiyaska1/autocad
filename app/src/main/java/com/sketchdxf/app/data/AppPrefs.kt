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
}

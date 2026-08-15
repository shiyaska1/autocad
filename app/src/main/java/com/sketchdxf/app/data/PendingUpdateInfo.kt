package com.sketchdxf.app.data

/**
 * Transient handoff of the fetched update-required message/URL across the nav boundary into
 * ForceUpdateScreen — same "Pending*" singleton tradeoff PendingSketchEditor already accepts
 * (not process-death-safe; fine here since BootScreen re-fetches from scratch on every launch
 * anyway). Set just before navigating to "force_update".
 */
object PendingUpdateInfo {
    @Volatile var message: String = ""
    @Volatile var updateUrl: String = ""

    fun set(message: String, updateUrl: String) {
        this.message = message
        this.updateUrl = updateUrl
    }
}

package com.sketchdxf.app.update

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Forces a Play Store update whenever one is available — the app is unusable on an old
 * build once a new one is published. Same approach as the POS Billing and Kerala Lottery apps.
 *
 * IMMEDIATE flow: Play shows a full-screen update screen and installs it. That screen has
 * no visible skip button, but a user can still bail out via the system Back gesture — Play
 * then just reports the update as available again rather than in-progress. To close that
 * loophole, [check] is called from both onCreate and onResume: backing out only returns the
 * customer to another blocking prompt, not to the app, so there is no way to reach a usable
 * screen while an update is pending. This only does anything for copies installed from
 * Play — a sideloaded APK is silently left alone, which is why every call is wrapped: on
 * those devices the Play API simply fails.
 */
object AppUpdater {

    private const val REQUEST_CODE = 4712

    fun check(activity: Activity) {
        runCatching {
            val manager = AppUpdateManagerFactory.create(activity)
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    // UPDATE_AVAILABLE: a new version was published since we last checked.
                    // DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS: the flow was showing and got
                    // interrupted (e.g. the OS killed the process) — pick it back up too.
                    val availability = info.updateAvailability()
                    val forceable = availability == UpdateAvailability.UPDATE_AVAILABLE ||
                        availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                    val allowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                    if (forceable && allowed) {
                        runCatching {
                            manager.startUpdateFlowForResult(info, AppUpdateType.IMMEDIATE, activity, REQUEST_CODE)
                        }
                    }
                }
                .addOnFailureListener { /* not installed from Play, or offline — ignore */ }
        }
    }
}

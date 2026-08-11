package com.sketchdxf.app.data

import android.content.Context
import android.provider.Settings
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Trial + device-locked activation — same scheme as the POS Billing app: a 30-day free trial,
 * then a device-locked key at 1/6/12/36/48 months. The activation key is an HMAC-SHA256 of the
 * device id (plus the milestone, for renewals beyond the first), so it can't be forged without
 * [SECRET]. To activate: read the customer their Device ID from the license screen, compute the
 * key (HMAC-SHA256, key = SECRET, message = deviceId[+milestone] exactly as shown, first 16 hex
 * chars, upper-case, dashed in 4s), and they type it in.
 */
object License {
    const val TRIAL_DAYS = 30

    const val SUPPORT_WHATSAPP = "919961128378"
    const val SUPPORT_PHONE = "+919961128378"
    const val SUPPORT_EMAIL = "shiyaska2009@gmail.com"

    const val BUY_URL = "https://wa.me/$SUPPORT_WHATSAPP?text=I%20want%20to%20buy%20Sketch%20DXF"

    fun buyUrlFor(deviceId: String): String {
        val msg = java.net.URLEncoder.encode(
            "I want to buy Sketch DXF. My Device ID is $deviceId", "UTF-8"
        )
        return "https://wa.me/$SUPPORT_WHATSAPP?text=$msg"
    }

    /** >>> CHANGE THIS to your own private secret before publishing. Keep it secret. <<< */
    private const val SECRET = "SKDXF-change-this-secret-2026"

    /** Stable per-device identifier (Android ID), shown to the user for activation. */
    fun deviceId(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return (if (id.isBlank()) "UNKNOWNDEVICE" else id).uppercase()
    }

    /** Renewal points, in months since installation. 1 is the end of the free trial. */
    val MILESTONES = listOf(1, 6, 12, 36, 48)

    /** Whole months elapsed since [installMillis], by calendar rather than by 30-day blocks. */
    fun monthsSince(installMillis: Long): Int {
        if (installMillis <= 0L) return 0
        val now = java.util.Calendar.getInstance()
        val probe = java.util.Calendar.getInstance().apply { timeInMillis = installMillis }
        var months = 0
        while (true) {
            probe.add(java.util.Calendar.MONTH, 1)
            if (probe.timeInMillis > now.timeInMillis) break
            months++
        }
        return months
    }

    /** The milestone the device has reached, or 0 while still inside the free trial. */
    fun dueMilestone(installMillis: Long): Int {
        if (!trialExpired(installMillis)) return 0
        val months = monthsSince(installMillis)
        return MILESTONES.filter { it <= maxOf(months, 1) }.maxOrNull() ?: 0
    }

    fun nextMilestone(milestone: Int): Int? = MILESTONES.firstOrNull { it > milestone }

    /**
     * Activation key for a device at a given renewal point. Milestone 1 is device-id-only;
     * later milestones mix the month count in (e.g. deviceId + "6" for the 6-month renewal),
     * so each renewal needs its own distinct key.
     */
    fun activationKey(deviceId: String, milestone: Int = 1): String {
        val message = if (milestone <= 1) deviceId.trim().uppercase()
        else deviceId.trim().uppercase() + milestone
        val hex = hmacHex(message).take(16).uppercase()
        return hex.chunked(4).joinToString("-")
    }

    fun isValid(deviceId: String, key: String, milestone: Int = 1): Boolean {
        val norm = key.uppercase().replace(Regex("[^0-9A-F]"), "")
        if (norm.isEmpty()) return false
        return activationKey(deviceId, milestone).replace("-", "") == norm
    }

    private fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun daysSince(installMillis: Long): Long {
        if (installMillis <= 0L) return 0L
        return (System.currentTimeMillis() - installMillis) / (1000L * 60 * 60 * 24)
    }

    fun trialExpired(installMillis: Long): Boolean = daysSince(installMillis) >= TRIAL_DAYS

    fun daysLeft(installMillis: Long): Int =
        (TRIAL_DAYS - daysSince(installMillis)).toInt().coerceIn(0, TRIAL_DAYS)
}

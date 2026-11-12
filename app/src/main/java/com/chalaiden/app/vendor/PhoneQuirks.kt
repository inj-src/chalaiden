package com.chalaiden.app.vendor

import android.os.Build

/**
 * Where a phone maker's USSD handling differs from stock Android, and what Chalaiden does about it.
 * Every quirk here was seen on a real phone; each says which one and when. Code elsewhere asks about
 * behaviour ([closesDialogOnSend]), never about brands, so a newly found phone is one line in [of].
 */
data class PhoneQuirks(
    /**
     * Send closes the USSD dialog straight away, and bKash's answer arrives in a new one, so for a few
     * seconds no dialog is up and Chalaiden's own screen is in front. Stock Android (and OxygenOS) keep
     * a "Running USSD code…" dialog up instead.
     *
     * vivo V2445A, Android 15 (OriginOS), 2026-11-10: about 2.8 s from Send to bKash's next menu on SIM 2.
     */
    val closesDialogOnSend: Boolean = false,
) {
    /**
     * How long Chalaiden may be back in front during a guide before bKash's dialog counts as gone.
     * Short where a dialog stays up between prompts, so Cancel lets the guide go quickly. Where Send
     * closes the dialog, it has to outlast bKash's slowest reply, and so it matches the keyboard's wait
     * for the next box.
     */
    val frontGapMillis: Long get() = if (closesDialogOnSend) SLOWEST_REPLY_MILLIS else 2_500L

    /**
     * How long the automatic driver may see no USSD dialog before the session counts as lost. Where a
     * "Running USSD code…" dialog stays up, a few seconds without one means it really went. Where Send
     * closes the dialog, the gap lasts until bKash answers, so it waits as long as it would for that
     * answer: bKash's slowest reply, and after the PIN the full wait for its receipt ([afterPinMillis]),
     * since calling a payment unknown while bKash is still working is worse than waiting.
     */
    fun lostAfterMillis(pinSent: Boolean, afterPinMillis: Long): Long = when {
        !closesDialogOnSend -> 6_000L
        pinSent -> afterPinMillis
        else -> SLOWEST_REPLY_MILLIS
    }

    companion object {
        /** Longer than bKash ever takes to send its next prompt after a reply (seconds, on a slow network). */
        const val SLOWEST_REPLY_MILLIS = 12_000L

        /** This phone's quirks. */
        val current: PhoneQuirks by lazy { of(Build.MANUFACTURER, Build.BRAND) }

        /** Quirks by maker. [brand] covers sub-brands sold under another maker's name (iQOO is vivo's). */
        fun of(manufacturer: String?, brand: String?): PhoneQuirks {
            val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
            return when {
                names.any { it == "vivo" || it == "iqoo" } -> PhoneQuirks(closesDialogOnSend = true)
                else -> PhoneQuirks()
            }
        }
    }
}

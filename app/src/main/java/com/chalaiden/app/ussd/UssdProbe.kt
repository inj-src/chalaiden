package com.chalaiden.app.ussd

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager

/**
 * Debug builds only: sends one USSD string with [TelephonyManager.sendUssdRequest], which needs no
 * root and shows no dialog, and writes what came back to `files/probe.log`. For finding out whether
 * guided mode can clear a stale bKash session ("Do you want to continue?") before opening the menu.
 * `adb shell am start -n com.chalaiden.app/.MainActivity --es ussd_probe '*247#'`
 */
object UssdProbe {
    fun send(context: Context, code: String, subscriptionId: Int?) {
        val log = java.io.File(context.filesDir, "probe.log")
        fun note(msg: String) = runCatching { log.appendText("${System.currentTimeMillis() % 100_000} $msg\n") }
        note("send '$code'")
        runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java)
                .let { if (subscriptionId != null) it.createForSubscriptionId(subscriptionId) else it }
            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) {
                    note("ok '$request' -> ${response.toString().replace("\n", " | ")}")
                }

                override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                    note("failed '$request' code=$failureCode")
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { note("threw $it") }
    }
}

package com.chalaiden.app.ussd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Root setup for the USSD driver. Chalaiden drives the system dialog with root instead of an
 * accessibility service, so there is deliberately no accessibility step here: nothing for
 * bKash's app to flag. Every call blocks on `su`, so run it off the main thread.
 */
object Root {
    private val permissions = listOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA,
    )

    /** True when root works; grants Chalaiden's own permissions and the overlay appop along the way. */
    fun ensureSetup(context: Context): Boolean {
        val shell = RootShell.open() ?: return false
        shell.use { sh ->
            val pkg = context.packageName
            permissions
                .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                .forEach { sh.exec("pm grant $pkg $it") }
            // The covering overlay is a normal "draw over other apps" window, granted here.
            sh.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            RootUssdDriver.restoreKeyboard(context, sh)
        }
        return true
    }
}

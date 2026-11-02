package com.chalaiden.app.ussd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Root shortcuts for a rooted phone: grant Chalaiden's permissions and keep its Accessibility
 * service switched on (OxygenOS turns it off whenever the app is force-stopped).
 * Every call blocks on `su`, so run it off the main thread.
 */
object Root {
    private val permissions = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CAMERA)

    fun run(command: String): Boolean = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        p.waitFor(8, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (e: Exception) {
        false
    }

    /** Returns true when everything is in place afterwards (or already was). */
    fun ensureSetup(context: Context): Boolean {
        val pkg = context.packageName
        val commands = permissions
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            .map { "pm grant $pkg $it" }
            .toMutableList()
        if (!UssdService.isEnabled(context)) {
            val ours = "$pkg/${UssdService::class.java.name}"
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').filter { it.isNotBlank() }
            commands += "settings put secure enabled_accessibility_services '${(enabled + ours).distinct().joinToString(":")}'"
            commands += "settings put secure accessibility_enabled 1"
        }
        if (commands.isEmpty()) return true
        return run(commands.joinToString(" && "))
    }
}

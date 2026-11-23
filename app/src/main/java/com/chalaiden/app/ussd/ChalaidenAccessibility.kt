package com.chalaiden.app.ussd

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.chalaiden.app.helper.PhoneDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Accessibility method: an ordinary, visible accessibility service the user switches on in
 * Settings › Accessibility, for phones with neither root nor Shizuku. It answers bKash's USSD menu the
 * same way the privileged helper does ([PhoneDialog]), and hosts Chalaiden's cover as an accessibility
 * overlay ([com.chalaiden.app.ui.RunCover]), so no "display over other apps" is needed either.
 *
 * Deliberately plain: it listens only to the phone app (`com.android.phone`, see
 * `res/xml/accessibility_service.xml`), does nothing until a session asks, and claims no
 * `isAccessibilityTool`. bKash's app showed no warning with such a service on (OnePlus, bKash 7.4.0,
 * 2026-11); if it ever warns again, the answer is guided mode or Shizuku, never hiding this.
 */
class ChalaidenAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        _running.value = true
    }

    // Sessions poll the dialog (see AccessibilityScreen); events carry nothing they need.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        gone()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        gone()
        super.onDestroy()
    }

    private fun gone() {
        if (instance === this) instance = null
        _running.value = false
    }

    /** The phone's USSD dialog, read through this service's view of the screen. */
    internal fun dialog() = PhoneDialog(windows = { windows }, active = { rootInActiveWindow })

    /**
     * Keeps the soft keyboard down while a session runs: the USSD box would pop it up, and the cover
     * needn't fight it. Android puts it back by itself if the service stops.
     */
    fun hideKeyboard(hidden: Boolean) {
        runCatching {
            softKeyboardController.showMode = if (hidden) SHOW_MODE_HIDDEN else SHOW_MODE_AUTO
        }
    }

    companion object {
        /** The running service, or null while it is switched off. */
        @Volatile var instance: ChalaidenAccessibility? = null
            private set

        private val _running = MutableStateFlow(false)

        /** True while the service is switched on and connected. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Switched on in Settings (it may take a moment more to connect; see [running]). */
        fun isEnabled(context: Context): Boolean = runCatching {
            val me = ComponentName(context, ChalaidenAccessibility::class.java)
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == me }
        }.getOrDefault(false)

        /** Settings › Accessibility, where the user switches Chalaiden on or off. */
        fun openSettings(context: Context) {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        /** Chalaiden's App info page, whose ⋮ menu holds "Allow restricted settings" (Android 13+). */
        fun openAppInfo(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

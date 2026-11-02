package com.chalaiden.app.ussd

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chalaiden.app.MainActivity
import com.chalaiden.app.ui.RunOverlay
import com.chalaiden.app.ui.theme.ChalaidenTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Answers the phone app's USSD dialogs for the running [UssdSession] and covers them
 * with Chalaiden's own screen. It ignores every window that isn't the phone app's.
 */
class UssdService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var overlay: ComposeView? = null
    private var overlayOwner: OverlayOwner? = null
    private var lastPhoneWindowAt = 0L

    override fun onServiceConnected() {
        UssdSession.performer = { action -> main.post { phoneRoot()?.let { perform(action, it) } } }
        UssdSession.sweeper = { then -> main.post { sweep(attempts = 6, then = then) } }
        scope.launch {
            UssdSession.current.collect { snap ->
                val state = snap?.state
                if (state == null || state is RunState.Finished) hideOverlay() else showOverlay()
                if (state is RunState.Finished) bringAppForward()
            }
        }
        main.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (UssdSession.current.value == null) return
        if (event.packageName?.toString() !in PHONE_PACKAGES) return
        val root = phoneRoot() ?: return
        lastPhoneWindowAt = System.currentTimeMillis()
        val dialog = read(root) ?: return
        perform(UssdSession.onDialog(dialog), root)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        UssdSession.performer = null
        UssdSession.sweeper = null
        main.removeCallbacks(watchdog)
        hideOverlay()
        scope.cancel()
        super.onDestroy()
    }

    /** Notices when the USSD window vanishes without a final message (dismissed, timed out). */
    private val watchdog = object : Runnable {
        override fun run() {
            if (UssdSession.isActive) {
                val now = System.currentTimeMillis()
                if (phoneRoot() != null) lastPhoneWindowAt = now
                if (lastPhoneWindowAt == 0L) lastPhoneWindowAt = now
                if (now - lastPhoneWindowAt > LOST_AFTER_MILLIS) UssdSession.onSessionLost()
            } else {
                lastPhoneWindowAt = 0L
            }
            main.postDelayed(this, 1_000)
        }
    }

    /**
     * The phone app stacks USSD dialogs: closing one can reveal an older prompt underneath.
     * Close them all before a new session so the engine never sees a stale prompt.
     */
    private fun sweep(attempts: Int, then: () -> Unit) {
        val root = phoneRoot()
        if (root == null || attempts == 0) return then()
        val nodes = flatten(root)
        button(nodes, "android:id/button2", "Cancel")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ?: button(nodes, "android:id/button1", "OK")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ?: performGlobalAction(GLOBAL_ACTION_BACK)
        main.postDelayed({ sweep(attempts - 1, then) }, 400)
    }

    private fun phoneRoot(): AccessibilityNodeInfo? =
        windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() in PHONE_PACKAGES }

    private fun read(root: AccessibilityNodeInfo): Dialog? {
        val nodes = flatten(root)
        if (nodes.any { it.text?.contains("Running USSD", ignoreCase = true) == true }) return Dialog.Progress
        val edit = nodes.firstOrNull { it.isEditable }
        val message = MESSAGE_IDS.firstNotNullOfOrNull { id -> nodes.firstOrNull { it.viewIdResourceName == id }?.text }
            ?: nodes.filter { !it.isEditable && it.className?.contains("Button") != true }
                .mapNotNull { it.text }.maxByOrNull { it.length }
        val text = message?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return if (edit != null) Dialog.Prompt(text) else Dialog.Notice(text)
    }

    private fun perform(action: Action, root: AccessibilityNodeInfo) {
        val nodes = flatten(root)
        when (action) {
            is Action.Reply -> {
                val edit = nodes.firstOrNull { it.isEditable } ?: return
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                }
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                button(nodes, "android:id/button1", "Send", "OK", "Reply")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            Action.Cancel -> button(nodes, "android:id/button2", "Cancel")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ?: performGlobalAction(GLOBAL_ACTION_BACK)
            Action.Dismiss -> button(nodes, "android:id/button1", "OK", "Close", "Cancel")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ?: performGlobalAction(GLOBAL_ACTION_BACK)
            Action.None -> Unit
        }
    }

    private fun button(nodes: List<AccessibilityNodeInfo>, id: String, vararg labels: String) =
        nodes.firstOrNull { it.viewIdResourceName == id && it.isClickable }
            ?: nodes.firstOrNull { n -> n.isClickable && labels.any { n.text?.toString().equals(it, ignoreCase = true) } }

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
        }
        return out
    }

    private fun showOverlay() {
        if (overlay != null) return
        val owner = OverlayOwner().also { it.start() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { ChalaidenTheme { RunOverlay() } }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        )
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
        overlayOwner = owner
    }

    private fun hideOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeViewImmediate(it) }
        overlayOwner?.stop()
        overlay = null
        overlayOwner = null
    }

    private fun bringAppForward() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    /** ComposeView needs a lifecycle; an overlay window has none of its own. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        private val PHONE_PACKAGES = setOf("com.android.phone")
        private val MESSAGE_IDS = listOf("com.android.phone:id/message_field", "android:id/message")
        private const val LOST_AFTER_MILLIS = 6_000L

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, UssdService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}

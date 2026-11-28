package com.chalaiden.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.chalaiden.app.ui.theme.tokens
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chalaiden.app.BuildConfig
import com.chalaiden.app.ui.theme.ChalaidenTheme
import com.chalaiden.app.ussd.ChalaidenAccessibility
import com.chalaiden.app.ussd.UssdDriver
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.UssdSession
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.chalaiden.app.onetap.OneTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Chalaiden's run screen ([RunOverlay]) in a "draw over other apps" window (or, with the Accessibility
 * method, the service's accessibility overlay), so it sits above the phone app's USSD dialogs for the
 * whole session and the user never sees them.
 *
 * The window is not focusable: key events (the driver's typing, ENTER, TAB) keep going to the phone
 * dialog underneath, while touches land on Chalaiden's own PIN pad and Cancel.
 */
object RunCover {
    private var started = false
    private var view: ComposeView? = null
    private var owner: CoverOwner? = null

    /** The window manager that added [view]; only that one can take it away again. */
    private var host: WindowManager? = null

    /** Drives the fade; the window is added before it fades in and removed after it fades out. */
    private val shown = mutableStateOf(false)
    private var removal: Job? = null
    private const val FADE_MILLIS = 220

    /** Call once with the application context; follows the session from then on. */
    fun attach(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val scope = MainScope()
        scope.launch {
            // Up for the session, and after it until the phone's last dialog has actually closed.
            // Down while One Tap's fingerprint sheet is up: this layer sits above it and would hide it.
            combine(UssdSession.current, UssdDriver.dialogUp, OneTap.prompting) { snap, dialogUp, prompting ->
                ((snap != null && snap.state !is RunState.Finished) || dialogUp) && !prompting
            }
                .distinctUntilChanged()
                .collectLatest { running ->
                    if (running) {
                        // The PIN screen first slides in inside the activity; take over once it has
                        // settled. Nothing is dialled before "Hold to pay", so there is no rush.
                        if (UssdSession.current.value?.state == RunState.Ready) delay(SETTLE_MILLIS)
                        show(app)
                    } else {
                        hide(app, scope)
                    }
                }
        }
    }

    private fun show(context: Context) {
        removal?.cancel()
        removal = null
        shown.value = true
        if (view != null) return
        // The Accessibility method's service hosts it as an accessibility overlay whenever it's running:
        // that layer is above every window the phone app can use. A plain overlay is not: HONOR's
        // MagicOS puts the USSD box that asks for input in a KEYGUARD_DIALOG window, which drew over a
        // plain cover at bKash's "Enter PIN:" (ELI-AN00, Android 16, 2026-11-24). The plain overlay is
        // for the other methods, and for phones without the service.
        val service = ChalaidenAccessibility.instance
        if (service == null && !Settings.canDrawOverlays(context)) return
        val windowContext: Context = service ?: context
        val type = if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        // An accessibility overlay sits above the system bars and is told of no insets, so RunOverlay's
        // own bar padding comes out as zero and the screen jumped up by the status bar's height
        // (OnePlus CPH2767, Android 16, 2026-11-23). It is padded here by the bars' real sizes instead.
        // Being above the status bar, it also painted over the clock and icons once it faded in; so it
        // starts just below the status bar, which stays visible over the activity's copy of this screen.
        val bars = service?.let(::barSizes)
        val density = context.resources.displayMetrics.density
        fun dp(px: Int) = (px / density).dp
        val padding = bars?.let { PaddingValues(start = dp(it.left), end = dp(it.right), bottom = dp(it.bottom)) }
        val o = CoverOwner().also { it.start() }
        val v = ComposeView(windowContext).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                ChalaidenTheme {
                    val visible = remember { MutableTransitionState(false) }
                    visible.targetState = shown.value
                    AnimatedVisibility(visible, enter = fadeIn(tween(FADE_MILLIS)), exit = fadeOut(tween(FADE_MILLIS))) {
                        if (padding == null) {
                            RunOverlay()
                        } else {
                            // Consumed, so a phone that does report insets here doesn't get them twice.
                            Box(Modifier.fillMaxSize().background(tokens.bg).padding(padding).consumeWindowInsets(WindowInsets.systemBars)) {
                                RunOverlay()
                            }
                        }
                    }
                }
            }
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // Keeps the PIN step out of screenshots and recents; debug builds leave it off to allow testing.
        if (!BuildConfig.DEBUG) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            bars?.let { screenHeight(wm) - it.top } ?: WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT, // so it can fade over the activity
        ).apply {
            gravity = Gravity.TOP
            if (bars != null) y = bars.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Overlays stop short of the system bars by default, and the phone's dialogs showed through
            // the navigation bar strip as they slid in and out. Cover the whole screen instead; the
            // content pads itself for the bars.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
        runCatching { wm.addView(v, params) }
            .onSuccess { view = v; owner = o; host = wm }
            .onFailure { o.stop() }
    }

    private fun hide(context: Context, scope: CoroutineScope) {
        shown.value = false
        removal?.cancel()
        removal = scope.launch {
            delay(FADE_MILLIS + 40L)
            view?.let { v -> runCatching { (host ?: context.getSystemService(WindowManager::class.java)).removeViewImmediate(v) } }
            owner?.stop()
            view = null
            owner = null
            host = null
        }
    }

    private const val SETTLE_MILLIS = 380L

    /** The status and navigation bars' sizes in pixels, whether or not they are showing: what the activity pads for. */
    private fun barSizes(context: Context): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            }.getOrNull()?.let { return Rect(it.left, it.top, it.right, it.bottom) }
        }
        fun dimen(name: String) = context.resources.getIdentifier(name, "dimen", "android")
            .takeIf { it != 0 }?.let(context.resources::getDimensionPixelSize) ?: 0
        return Rect(0, dimen("status_bar_height"), 0, dimen("navigation_bar_height"))
    }

    /** The whole display's height, bars included. */
    private fun screenHeight(wm: WindowManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height()
        else DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.heightPixels

    /** ComposeView needs a lifecycle; an overlay window has none of its own. */
    private class CoverOwner : LifecycleOwner, SavedStateRegistryOwner {
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
}

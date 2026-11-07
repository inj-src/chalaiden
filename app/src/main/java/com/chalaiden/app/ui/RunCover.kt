package com.chalaiden.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
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
import com.chalaiden.app.ussd.RootUssdDriver
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.UssdSession
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Chalaiden's run screen ([RunOverlay]) in a "draw over other apps" window, so it sits above the
 * phone app's USSD dialogs for the whole session and the user never sees them.
 *
 * The window is not focusable: key events (the driver's typing, ENTER, TAB) keep going to the phone
 * dialog underneath, while touches land on Chalaiden's own PIN pad and Cancel.
 */
object RunCover {
    private var started = false
    private var view: ComposeView? = null
    private var owner: CoverOwner? = null

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
            combine(UssdSession.current, RootUssdDriver.dialogUp) { snap, dialogUp ->
                (snap != null && snap.state !is RunState.Finished) || dialogUp
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
        if (view != null || !Settings.canDrawOverlays(context)) return
        val o = CoverOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                ChalaidenTheme {
                    val visible = remember { MutableTransitionState(false) }
                    visible.targetState = shown.value
                    AnimatedVisibility(visible, enter = fadeIn(tween(FADE_MILLIS)), exit = fadeOut(tween(FADE_MILLIS))) {
                        RunOverlay()
                    }
                }
            }
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // Keeps the PIN step out of screenshots and recents; debug builds leave it off to allow testing.
        if (!BuildConfig.DEBUG) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT, // so it can fade over the activity
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Overlays stop short of the system bars by default, and the phone's dialogs showed through
            // the navigation bar strip as they slid in and out. Cover the whole screen instead; the
            // content pads itself for the bars.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
        runCatching { context.getSystemService(WindowManager::class.java).addView(v, params) }
            .onSuccess { view = v; owner = o }
            .onFailure { o.stop() }
    }

    private fun hide(context: Context, scope: CoroutineScope) {
        shown.value = false
        removal?.cancel()
        removal = scope.launch {
            delay(FADE_MILLIS + 40L)
            view?.let { runCatching { context.getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
            owner?.stop()
            view = null
            owner = null
        }
    }

    private const val SETTLE_MILLIS = 380L

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

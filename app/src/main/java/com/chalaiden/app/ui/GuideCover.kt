package com.chalaiden.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chalaiden.app.ui.theme.ChalaidenTheme
import com.chalaiden.app.ussd.GuideSession
import com.chalaiden.app.ussd.GuideStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Guided mode's card ([GuideCard]) in a small "draw over other apps" window at the top of the screen,
 * above bKash's USSD dialog, while the user types the replies themselves.
 *
 * Unlike [RunCover] it only takes the strip it draws: it is not focusable, so the keyboard and typing
 * stay with bKash's box, and touches anywhere else reach bKash's dialog. It is hidden while Chalaiden's
 * own screen is in front, which shows the same steps.
 */
object GuideCover {
    private var started = false
    private var view: ComposeView? = null
    private var owner: CoverOwner? = null
    private val shown = mutableStateOf(false)
    private var removal: Job? = null
    private const val FADE_MILLIS = 200
    private const val IDLE_MILLIS = 4 * 60_000L
    private const val PIN_IDLE_MILLIS = 200_000L

    /** Call once; follows [GuideSession] from then on. */
    fun attach(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val scope = MainScope()
        // A guide nobody moves on for this long has outlived bKash's dialog (bKash drops a session
        // after ~190 s at its PIN step), e.g. the user went to the home screen; put the card away.
        scope.launch {
            GuideSession.state.collectLatest { guide ->
                if (guide == null) return@collectLatest
                // At the PIN the card has no way to see bKash give up (~190 s), so it gives up just after.
                delay(if (guide.step.kind == GuideStep.Kind.Pin) PIN_IDLE_MILLIS else IDLE_MILLIS)
                GuideSession.end(GuideSession.End.Gone)
            }
        }
        scope.launch {
            combine(GuideSession.state, GuideSession.appVisible) { guide, appVisible -> guide != null && !appVisible }
                .distinctUntilChanged()
                .collectLatest { up -> if (up) show(app) else hide(app, scope) }
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
                    val guide by GuideSession.state.collectAsState()
                    AnimatedVisibility(
                        visible,
                        enter = fadeIn(tween(FADE_MILLIS)) + slideInVertically(tween(FADE_MILLIS + 80)) { -it / 3 },
                        exit = fadeOut(tween(FADE_MILLIS)) + slideOutVertically(tween(FADE_MILLIS)) { -it / 3 },
                    ) {
                        guide?.let { g ->
                            // No Done: once bKash's last dialog closes, Chalaiden is in front again and ends it.
                            GuideCard(g, floating = true)
                        }
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable (which also makes it not touch-modal): bKash's box keeps the keyboard and the
            // typing, and touches outside the card go through to bKash's dialog.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            // Android 11+ keeps overlays out of the status bar by itself; before that, step under it.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) y = statusBarHeight(context)
        }
        runCatching { context.getSystemService(WindowManager::class.java).addView(v, params) }
            .onSuccess { view = v; owner = o }
            .onFailure { o.stop() }
    }

    private fun hide(context: Context, scope: CoroutineScope) {
        shown.value = false
        removal?.cancel()
        removal = scope.launch {
            delay(FADE_MILLIS + 60L)
            view?.let { runCatching { context.getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
            owner?.stop()
            view = null
            owner = null
        }
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

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

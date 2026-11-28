package com.chalaiden.app.ui

import com.chalaiden.app.ussd.Outcome
import com.chalaiden.app.ussd.RunState
import com.chalaiden.app.ussd.UssdFlow
import com.chalaiden.app.ussd.UssdSession
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * After "Hold to pay" the step list plays from the top, each step showing at least [STEP_MILLIS]. The menu
 * was usually walked while the PIN was typed, so without this every step would tick off at once and the
 * screen would jump straight to the end. bKash itself is never held back: only what is shown waits.
 *
 * The list never runs ahead of the real session, and a failure or Cancel ends the replay at once. A success
 * waits for it (see [caughtUp]), so the result never appears over a list still ticking.
 *
 * One copy for both screens: the activity and [RunCover] each draw [RunOverlay].
 */
object StepReplay {
    const val STEP_MILLIS = 200L

    private val _shown = MutableStateFlow<Int?>(null)

    /** Lines of the step list ticked off on screen; null before the hold. */
    val shown: StateFlow<Int?> = _shown.asStateFlow()
    private var started = false

    fun attach() {
        if (started) return
        started = true
        MainScope().launch {
            UssdSession.current.map { it?.confirmed == true }.distinctUntilChanged().collectLatest { held ->
                _shown.value = if (held) 0 else null
                if (held) play()
            }
        }
    }

    private suspend fun play() {
        val total = UssdSession.current.value?.flow?.let(::lines) ?: return
        var since = System.currentTimeMillis()
        while (true) {
            val at = _shown.value ?: return
            if (at >= total) return
            val s = UssdSession.current.first { it == null || !it.confirmed || failed(it.state) || reached(it.state, total) > at }
            if (s == null || !s.confirmed || failed(s.state)) return
            delay(STEP_MILLIS - (System.currentTimeMillis() - since))
            _shown.value = at + 1
            since = System.currentTimeMillis()
        }
    }

    /** Lines in the step list: opening the menu, then each step but the PIN. */
    fun lines(flow: UssdFlow): Int = flow.steps.size

    /** How far the real session has got, in lines of the step list. */
    fun reached(state: RunState, total: Int): Int = when (state) {
        RunState.Ready -> 0
        is RunState.Working -> state.stepIndex
        is RunState.AwaitingAmount -> state.stepIndex
        else -> total // at the summary or past it: every line is done
    }

    /** Ended some other way than paid: the replay stops and the real state shows at once. */
    fun failed(state: RunState): Boolean = state is RunState.Finished && state.outcome !is Outcome.Success

    /** [shown] has caught up with [snap], or no longer needs to: what is on screen is the real state. */
    fun caughtUp(snap: UssdSession.Snapshot, shown: Int?): Boolean =
        !snap.confirmed || failed(snap.state) || (shown ?: 0) >= lines(snap.flow)
}

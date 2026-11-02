package com.chalaiden.app.ussd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single running session, shared by the screens (which start it and show the result)
 * and the accessibility service (which feeds it dialogs and performs its actions).
 */
object UssdSession {
    /**
     * After a session ends bKash can still send a trailing prompt ("Do you want to continue?
     * 1 - Yes 2 - No"). For this long after the end, any bKash dialog is closed, never answered.
     */
    const val TAIL_MILLIS = 20_000L

    /** bKash dropped an idle PIN step after ~190 s on Grameenphone (measured 2026-11-02); keep a margin. */
    const val PIN_WINDOW_MILLIS = 170_000L

    data class Snapshot(val flow: UssdFlow, val state: RunState)

    private val _current = MutableStateFlow<Snapshot?>(null)
    val current: StateFlow<Snapshot?> = _current.asStateFlow()

    private var engine: UssdEngine? = null
    private var endedAt = 0L

    /** Set by the accessibility service while it is connected. */
    @Volatile var performer: ((Action) -> Unit)? = null

    /** Closes leftover phone dialogs, then runs the callback. Set by the accessibility service. */
    @Volatile var sweeper: ((then: () -> Unit) -> Unit)? = null

    val isActive: Boolean get() = engine?.state.let { it != null && it !is RunState.Finished }

    /** True while the service should look at phone dialogs: during a session and its tail. */
    val wantsDialogs: Boolean
        @Synchronized get() = engine != null || System.currentTimeMillis() - endedAt < TAIL_MILLIS

    @Synchronized fun begin(flow: UssdFlow) {
        engine = UssdEngine(flow)
        publish()
    }

    @Synchronized fun onDialog(dialog: Dialog, now: Long = System.currentTimeMillis()): Action {
        val e = engine
        if (e == null) {
            if (now - endedAt >= TAIL_MILLIS) return Action.None
            return when (dialog) {
                is Dialog.Prompt -> Action.Cancel
                is Dialog.Notice -> Action.Dismiss
                Dialog.Progress -> Action.None
            }
        }
        return e.onDialog(dialog).also { publish() }
    }

    @Synchronized fun submitPin(pin: String) {
        val action = engine?.submitPin(pin) ?: return
        publish()
        performer?.invoke(action)
    }

    @Synchronized fun cancel() {
        val action = engine?.cancel() ?: return
        publish()
        performer?.invoke(action)
    }

    @Synchronized fun onSessionLost() {
        engine?.onSessionLost()
        publish()
    }

    /** Called once the result has been shown, so a stale session can't be resumed. */
    @Synchronized fun clear(now: Long = System.currentTimeMillis()) {
        if (engine != null) endedAt = now
        engine = null
        _current.value = null
    }

    private fun publish() {
        val e = engine ?: return
        _current.value = Snapshot(e.flow, e.state)
    }
}

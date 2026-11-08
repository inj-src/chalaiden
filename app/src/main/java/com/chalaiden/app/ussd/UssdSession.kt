package com.chalaiden.app.ussd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single running session, shared by the screens (which start it and show the result)
 * and [UssdDriver] (which feeds it dialogs and performs its actions).
 */
object UssdSession {
    /**
     * After a session ends bKash can still send a trailing prompt ("Do you want to continue?
     * 1 - Yes 2 - No"). For this long after the end, any bKash dialog is closed, never answered.
     */
    const val TAIL_MILLIS = 20_000L

    /** Before the PIN, bKash answers within seconds. This long with nothing new means the session is stuck. */
    const val STALL_MILLIS = 25_000L

    /** After the PIN, give bKash longer to answer before calling the outcome unknown. */
    const val CONFIRM_STALL_MILLIS = 45_000L

    /** bKash dropped an idle PIN step after ~190 s on Grameenphone (measured 2026-11-02); keep a margin. */
    const val PIN_WINDOW_MILLIS = 170_000L

    /**
     * A menu warmed up while the PIN is typed is closed this long before bKash would drop it, so a
     * late "Hold to pay" never sends the PIN into a session that is about to die. The hold dials again.
     */
    const val WARM_EXPIRY_MILLIS = PIN_WINDOW_MILLIS - 30_000L

    /** [confirmed]: the user finished "Hold to pay". Until then the PIN screen stays up, even while the menu warms up. */
    data class Snapshot(val flow: UssdFlow, val state: RunState, val confirmed: Boolean = false)

    private val _current = MutableStateFlow<Snapshot?>(null)
    val current: StateFlow<Snapshot?> = _current.asStateFlow()

    private var engine: UssdEngine? = null
    private var endedAt = 0L

    /** The flow on the PIN screen, between [prepare] and the end of its session (or [abandon]). */
    private var staged: UssdFlow? = null
    private var confirmed = false

    /**
     * Starts the automatic driver for a flow; set by the activity, because only a foreground activity can
     * dial `*247#`. Called on the first PIN digit, so the menu walks while the user types.
     */
    @Volatile var starter: ((UssdFlow) -> Unit)? = null

    /** Performs an action on the phone dialog, off the caller's thread. Set by [UssdDriver]. */
    @Volatile var performer: ((Action) -> Unit)? = null

    val isActive: Boolean get() = engine?.state.let { it != null && it !is RunState.Finished }

    /** True while the driver should look at phone dialogs: during a session and its tail. */
    val wantsDialogs: Boolean
        @Synchronized get() = engine != null || System.currentTimeMillis() - endedAt < TAIL_MILLIS

    /** Shows the PIN screen for [flow]. Nothing is dialled until the user presses "Hold to pay". */
    @Synchronized fun prepare(flow: UssdFlow) {
        engine = null
        staged = flow
        confirmed = false
        publish()
    }

    /** Called by the driver as it dials. */
    @Synchronized fun begin(flow: UssdFlow) {
        engine = UssdEngine(flow)
        publish()
    }

    /**
     * The user started typing the PIN (or pressed "Hold to pay"): start walking the menu now, so bKash's
     * summary is usually waiting by the time the hold completes. No-op while a session is already warm.
     */
    fun warmUp() {
        val flow = synchronized(this) { staged.takeIf { engine == null && !confirmed } } ?: return
        starter?.invoke(flow)
    }

    /**
     * Closes a warm menu that wasn't confirmed (nothing was sent; the PIN never left Chalaiden). The
     * driver keeps watching through the tail and closes what's left.
     */
    @Synchronized private fun coolDown(now: Long = System.currentTimeMillis()) {
        if (confirmed) return
        val action = engine?.cancel()
        if (engine != null) endedAt = now
        engine = null
        publish()
        action?.let { performer?.invoke(it) }
    }

    /** The hold completed: the PIN goes in as soon as bKash's summary matches (see [UssdEngine.presetPin]). */
    @Synchronized fun confirm(pin: String) {
        if (staged == null || confirmed) return
        confirmed = true
        val action = engine?.presetPin(pin)
        if (engine == null) {
            // The press didn't start a session (the driver failed to start); nothing will answer.
            engine = UssdEngine(staged!!).also { it.onReplyUndelivered() }
        }
        publish()
        action?.takeIf { it != Action.None }?.let { performer?.invoke(it) }
    }

    /** "Hold to pay" again after bKash showed a fee: send the PIN entered earlier. */
    @Synchronized fun confirmFee() {
        val action = engine?.confirmFee() ?: return
        publish()
        if (action != Action.None) performer?.invoke(action)
    }

    /** Left the PIN screen (back or Cancel): end any warm session and drop the screen. */
    @Synchronized fun abandon(now: Long = System.currentTimeMillis()) {
        if (confirmed && isActive) return cancel()
        coolDown(now)
        staged = null
        confirmed = false
        _current.value = null
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
        val before = e.state
        return e.onDialog(dialog).also { settle(before, now); publish() }
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

    /** Called on every poll by the driver. Ends a session that stopped moving; returns what to do with the dialog. */
    @Synchronized fun checkStall(now: Long = System.currentTimeMillis()): Action {
        val e = engine ?: return Action.None
        val waiting = e.state as? RunState.AwaitingPin
        if (!confirmed && waiting != null && now - waiting.sinceMillis > WARM_EXPIRY_MILLIS) {
            // Warm, but the user is still on the PIN screen: close it before bKash does.
            val action = e.cancel()
            engine = null
            endedAt = now
            publish()
            return action
        }
        val idle = now - e.lastChangeAt
        val stuck = when (e.state) {
            is RunState.Working -> idle > STALL_MILLIS
            RunState.Confirming -> idle > CONFIRM_STALL_MILLIS
            else -> false
        }
        if (!stuck) return Action.None
        e.onSessionLost()
        publish()
        return Action.Cancel
    }

    @Synchronized fun onReplyUndelivered() {
        engine?.onReplyUndelivered()
        publish()
    }

    @Synchronized fun onSessionLost(now: Long = System.currentTimeMillis()) {
        val before = engine?.state
        engine?.onSessionLost()
        settle(before, now)
        publish()
    }

    /**
     * bKash dropped a warm session at its PIN step before the user confirmed (they were slow to
     * type). Nothing was sent, so it is not a failure to show: go back to ready, and the next digit
     * or hold dials again. A failure while walking the menu (a wrong number, bKash down) is still shown.
     */
    private fun settle(before: RunState?, now: Long) {
        val e = engine ?: return
        if (confirmed || before !is RunState.AwaitingPin || e.state !is RunState.Finished) return
        engine = null
        endedAt = now
    }

    /** Called once the result has been shown, so a stale session can't be resumed. */
    @Synchronized fun clear(now: Long = System.currentTimeMillis()) {
        if (engine != null) endedAt = now
        engine = null
        staged = null
        confirmed = false
        _current.value = null
    }

    /**
     * Debug builds only: shows a made-up run screen. Ignored while a real session is staged or running.
     * With nothing staged, [warmUp] and [confirm] do nothing, so the demo can't dial.
     */
    @Synchronized fun debugShow(snapshot: Snapshot?) {
        if (!com.chalaiden.app.BuildConfig.DEBUG || engine != null || staged != null) return
        _current.value = snapshot
    }

    private fun publish() {
        val e = engine
        _current.value = when {
            e != null -> Snapshot(e.flow, e.state, confirmed)
            staged != null -> Snapshot(staged!!, RunState.Ready, confirmed)
            else -> null
        }
    }
}

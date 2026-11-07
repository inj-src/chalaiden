package com.chalaiden.app.ussd

import android.content.Context
import android.util.Log
import com.chalaiden.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the system USSD dialog with root instead of an accessibility service: it reads the dialog
 * through a [UssdScreen], feeds the text to the shared [UssdEngine] through [UssdSession], and
 * performs the engine's answers. The fast [HelperScreen] is used when it starts; otherwise the slower
 * [DumpScreen]. Either way Chalaiden's cover ([com.chalaiden.app.ui.RunCover]) hides the dialogs.
 */
object RootUssdDriver {
    private const val TAG = "ChalaidenUssd"

    /** The phone dialog may vanish between exchanges for a moment; this long without it means it's gone. */
    private const val LOST_AFTER_MILLIS = 6_000L

    /**
     * After a session ends, this long with no dialog (counted from the dial or the last dialog) means
     * the phone is done. A released "Hold to pay" ends the session before bKash's menu even appears
     * (~2.5 s after dialling), so the keyboards and the helper wait this out before they are released.
     */
    private const val QUIET_MILLIS = 5_000L

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    /**
     * Bumped on every start. A released "Hold to pay" ends one driver while the next press starts
     * another; the old one may still be finishing and must never act on the new session.
     */
    @Volatile private var generation = 0
    @Volatile private var shell: RootShell? = null
    @Volatile private var screen: UssdScreen? = null
    @Volatile private var traceFile: java.io.File? = null

    private val _dialogUp = MutableStateFlow(false)

    /** True while a phone USSD dialog is on screen, so the cover can stay up until it has really gone. */
    val dialogUp: StateFlow<Boolean> = _dialogUp.asStateFlow()

    /** Debug builds only: OxygenOS hides app logcat, so the driver writes its steps to a file. Never reply values. */
    private fun trace(msg: String) {
        if (!BuildConfig.DEBUG) return
        runCatching { traceFile?.appendText("${System.currentTimeMillis() % 100_000} $msg\n") }
    }

    /** [dial] opens the USSD session; it must be a foreground launch, so the activity runs it, not root. */
    fun start(context: Context, flow: UssdFlow, dial: suspend () -> Unit) {
        stop()
        val gen = ++generation
        val prefs = Prefs(context.applicationContext)
        val apk = context.applicationInfo.sourceDir
        traceFile = java.io.File(context.filesDir, "driver.log").also { it.writeText("") }
        UssdSession.begin(flow)
        // Never on the caller's thread: the PIN arrives from the UI, and su I/O would block it.
        UssdSession.performer = { action -> io.launch { runCatching { execute(action, gen = gen) }.onFailure { fail(gen, it) } } }
        job = io.launch { runCatching { drive(gen, prefs, apk, dial) }.onFailure { fail(gen, it) } }
    }

    fun stop() {
        job?.cancel()
        job = null
        _dialogUp.value = false
        screen?.close()
        screen = null
        shell?.close()
        shell = null
    }

    /** Puts back keyboards a killed session left disabled. Safe to call any time no session runs. */
    fun restoreKeyboard(context: Context, sh: RootShell) {
        if (UssdSession.isActive) return
        val prefs = Prefs(context.applicationContext)
        val saved = prefs.imeToRestore ?: return
        // Only forget them once they are really back; otherwise the next launch retries.
        if (runCatching { keyboardsOn(sh, saved) }.isSuccess) prefs.imeToRestore = null
    }

    /**
     * The system USSD field pops the soft keyboard, which draws above Chalaiden's cover. Every enabled
     * keyboard is switched off for the session (switching off one only promotes the next, e.g. voice
     * typing). Returns what to restore: the default first, then the rest, one per line.
     */
    private fun keyboardsOff(sh: RootShell): String? {
        val default = sh.exec("settings get secure default_input_method").trim().takeIf { it.isNotBlank() && it != "null" }
        val enabled = sh.exec("ime list -s").lines().map { it.trim() }.filter { it.contains('/') }
        val all = (listOfNotNull(default) + enabled).distinct()
        if (all.isEmpty()) return null
        return all.joinToString("\n")
    }

    private fun keyboardsOn(sh: RootShell, saved: String) {
        val ids = saved.lines().filter { it.isNotBlank() }
        val cmds = ids.map { "ime enable '$it' >/dev/null 2>&1" } + listOfNotNull(ids.firstOrNull()?.let { "ime set '$it' >/dev/null 2>&1" })
        sh.exec(cmds.joinToString("; "))
    }

    private suspend fun CoroutineScope.drive(gen: Int, prefs: Prefs, apk: String, dial: suspend () -> Unit) {
        val sh = RootShell.open() ?: return fail(gen, IllegalStateException("No root"))
        fun current() = gen == generation
        if (!current()) return sh.close()
        shell = sh
        // Saved before switching off, so a killed session can still put the keyboards back.
        var keyboards = prefs.imeToRestore ?: keyboardsOff(sh)
        fun keyboardsBack() {
            val saved = keyboards ?: return
            keyboards = null
            // Only forget them once they are really back; otherwise the next launch retries.
            if (runCatching { keyboardsOn(sh, saved) }.isSuccess) prefs.imeToRestore = null
        }
        var opened: UssdScreen? = null // for the finally block
        try {
            keyboards?.let { saved ->
                prefs.imeToRestore = saved
                sh.exec(saved.lines().joinToString("; ") { "ime disable '$it' >/dev/null 2>&1" })
            }
            var ui: UssdScreen = HelperScreen.open(apk) ?: DumpScreen(sh)
            opened = ui
            if (!current()) return
            screen = ui
            trace("screen: ${ui::class.simpleName}, keyboards off: ${keyboards?.lines()?.size ?: 0}")
            sweep(ui)
            dial()
            trace("dialled")

            var lastSeenAt = System.currentTimeMillis() // the dial counts as the last sighting
            var lastTraced: Dialog? = null
            // Keep watching through the tail too, so a trailing "Do you want to continue?" is closed.
            while (isActive && current() && UssdSession.wantsDialogs) {
                val now = System.currentTimeMillis()
                val look = ui.look()
                if (!current()) break
                _dialogUp.value = look != null
                // The user's keyboard comes back as soon as the session is over, not after the tail.
                if (look == null && !UssdSession.isActive && now - lastSeenAt > QUIET_MILLIS) {
                    if (keyboards != null) keyboardsBack()
                    // Done and nothing on screen: drop the helper's UI connection now rather than after
                    // the tail, in case the bKash app is opened next. A late prompt is rare, and the
                    // dump fallback (a cheap focus check until one appears) still closes it.
                    if (ui is HelperScreen) {
                        ui.close()
                        ui = DumpScreen(sh)
                        opened = ui
                        screen = ui
                        trace("helper closed")
                    }
                }
                if (look != null) {
                    lastSeenAt = now
                    look.dialog?.let { dialog ->
                        val action = UssdSession.onDialog(dialog)
                        if (action != Action.None || dialog != lastTraced) {
                            trace("${dialog::class.simpleName} '${Prompt.lastLine(dialogText(dialog))}' -> ${action::class.simpleName} [${UssdSession.current.value?.state}]")
                        }
                        lastTraced = dialog
                        if (action != Action.None) execute(action, look, gen)
                    }
                } else if (UssdSession.isActive && now - lastSeenAt > LOST_AFTER_MILLIS) {
                    trace("lost")
                    UssdSession.onSessionLost()
                }
                UssdSession.checkStall(now).let { if (it != Action.None) execute(it, gen = gen) }
                delay(ui.pollMillis)
            }
        } finally {
            // Only tidy what is this driver's own; a newer one may already be running.
            if (current()) {
                _dialogUp.value = false
                if (screen === opened) screen = null
                keyboardsBack()
            }
            opened?.close()
        }
    }

    /** One action against the current dialog. Synchronized: the poll loop and the PIN pad both call it. */
    @Synchronized private fun execute(action: Action, seen: Look? = null, gen: Int) {
        // A superseded driver's action (e.g. a released hold's Cancel) is dropped; the next driver's
        // sweep closes whatever it left.
        if (gen != generation) return
        val ui = screen ?: return
        when (action) {
            is Action.Reply -> {
                // The prompt it answers: named by the engine for the PIN, else the one just read.
                val expect = action.to ?: (seen?.dialog as? Dialog.Prompt)?.text
                if (expect == null || !ui.reply(action.text, expect, seen)) {
                    // Nothing was sent (this may be the PIN): the field or its prompt had gone.
                    trace("reply undelivered: prompt gone or changed")
                    UssdSession.onReplyUndelivered()
                }
            }
            Action.Cancel -> close(ui, seen ?: ui.look())
            Action.Dismiss -> ui.press("button1") || ui.press("button2") || run { ui.back(); true }
            Action.None -> Unit
        }
    }

    /** Close any leftover USSD dialog before a new session so the engine never sees a stale prompt. */
    private fun sweep(ui: UssdScreen) {
        repeat(6) {
            close(ui, ui.look() ?: return)
            Thread.sleep(400)
        }
    }

    /**
     * Cancel, never Send: on a prompt button1 sends whatever is in the reply box to bKash. Only a
     * dialog without a reply box (a notice, where button1 is OK) may be closed with button1.
     */
    private fun close(ui: UssdScreen, look: Look?) {
        if (ui.press("button2")) return
        if (look != null && look.field == null && look.dialog !is Dialog.Prompt && ui.press("button1")) return
        ui.back()
    }

    private fun dialogText(d: Dialog) = when (d) { is Dialog.Prompt -> d.text; is Dialog.Notice -> d.text; Dialog.Progress -> "" }

    private fun fail(gen: Int, e: Throwable) {
        trace("failed: $e")
        if (BuildConfig.DEBUG) Log.w(TAG, "driver failed", e)
        if (gen == generation) UssdSession.onSessionLost()
    }
}

package com.chalaiden.app.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chalaiden.app.ui.components.PinDots
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.theme.ChalaidenTheme
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.vendor.PhoneQuirks
import com.chalaiden.app.ussd.GuideSession
import com.chalaiden.app.ussd.GuideStep
import com.chalaiden.app.ussd.KeyMove
import com.chalaiden.app.ussd.KeyboardFill
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Guided mode's keyboard. While a guide runs it types each reply into bKash's USSD box by itself, so
 * the user only checks it and taps Send. At the PIN it shows a PIN pad: the user types the PIN, and each
 * digit goes straight into bKash's box. It never fills the PIN in, reads it back or keeps it, and keeps
 * nothing else it types either. Anywhere but bKash's box it switches straight back to the user's own keyboard.
 *
 * Android lets only the user pick a keyboard, so Chalaiden shows the picker before it dials
 * ([ChalaidenKeyboard.pick]); this is the only way to type into another app without root,
 * Shizuku or an accessibility service.
 */
class KeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = MainScope()

    /** True while bKash's box (the phone app's USSD field) is the box being typed into. */
    private var inPhoneBox = false

    /**
     * How many PIN digits the user has typed into bKash's box, for the dots on the PIN pad. Counted as
     * they're typed, so the box (with the PIN in it) is never read back.
     */
    private val pinTyped = mutableIntStateOf(0)

    /** The PIN box closed with digits in it: Send, most likely. The next bKash box is its answer. */
    private var pinSent = false

    /**
     * Room for the system's bar under the keyboard (its ⌄ and keyboard-switch buttons, which Android 15+
     * draws over the keyboard's own window), in px. Read from the view's insets.
     */
    private val bottomInset = mutableIntStateOf(0)

    /** Ends the guide if no new bKash box turns up after the last one closed (see [onFinishInput]). */
    private var goneCheck: Job? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        GuideSession.bind(this)
        scope.launch {
            var last: GuideSession.State? = null
            GuideSession.state.collect { guide ->
                val before = last
                last = guide
                when {
                    // The guide ended before the PIN (Cancel, or the card closed): give the user's keyboard
                    // back now, not the next time they tap a text box somewhere else.
                    guide == null -> {
                        pinSent = false
                        if (before != null) switchAway(quiet = true)
                    }
                    // Next on the card: the reply in bKash's box is for the step the user skipped.
                    before != null && guide.index != before.index && guide.filled != guide.index && inPhoneBox -> follow(guide)
                }
            }
        }
    }

    /** Puts the card's new step's reply in bKash's box in place of the old one; none for the PIN. */
    private fun follow(guide: GuideSession.State) {
        val step = guide.step
        pinSent = false
        if (step.kind == GuideStep.Kind.Pin) {
            replaceBox("")
            pinTyped.intValue = 0
            return showPad()
        }
        replaceBox(step.fill.orEmpty())
        if (step.fill != null) GuideSession.filled(guide.index)
    }

    private fun replaceBox(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(ic.getTextBeforeCursor(256, 0)?.length ?: 0, ic.getTextAfterCursor(256, 0)?.length ?: 0)
        if (text.isNotEmpty()) ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    override fun onCreateInputView(): View {
        // Compose finds its lifecycle through the window, and an input method's window has none of its own.
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@KeyboardService)
            setViewTreeSavedStateRegistryOwner(this@KeyboardService)
            // Compose's own inset modifiers see nothing in an input method's window, so the bar is
            // measured here. Whichever reports it (navigation bar, or its tappable area) wins.
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                bottomInset.intValue = maxOf(
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
                    insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom,
                )
                insets
            }
            setContent {
                ChalaidenTheme {
                    val bottom = with(LocalDensity.current) { bottomInset.intValue.toDp() }
                    Panel(pinTyped = pinTyped.intValue, bottom = bottom, onKey = ::key, onDelete = ::delete)
                }
            }
        }
    }

    /**
     * bKash's box takes focus as soon as its dialog opens, before anyone taps it, so the reply is typed
     * here rather than when the keyboard shows. Only the phone app's box is touched; a focus with no
     * text box behind it (TYPE_NULL, e.g. Chalaiden's own screen) is ignored.
     */
    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        inPhoneBox = info.inputType != EditorInfo.TYPE_NULL && info.packageName == KeyboardFill.PHONE_PACKAGE
        if (!inPhoneBox) return
        goneCheck?.cancel()
        act(KeyboardFill.decide(GuideSession.state.value, inPhone = true, empty = boxEmpty(), pinSent = pinSent))
    }

    /**
     * bKash's box closed: Send (the next prompt follows within seconds), or the dialog went away for good
     * (Cancel, bKash timing out) while Chalaiden isn't in front to notice. With no new box for
     * [GONE_MILLIS], the guide is over, the same as when Chalaiden comes back to the front. That holds
     * at the PIN too: Cancel there, or Send with bKash's answer shown without a box ("Cannot pay self.",
     * any failure, an OK-only receipt). After the PIN nothing more is asked of the user, so the wait
     * there is short ([AFTER_PIN_MILLIS]).
     */
    override fun onFinishInput() {
        super.onFinishInput()
        boxClosed()
    }

    /**
     * Android can drop the connection to bKash's box without finishing its input first, e.g. when the
     * dialog is replaced by an OK-only one. Treated the same as the box closing, so the card can't stay up.
     */
    override fun onUnbindInput() {
        super.onUnbindInput()
        boxClosed()
    }

    private fun boxClosed() {
        val wasPhone = inPhoneBox
        inPhoneBox = false
        val guide = GuideSession.state.value
        if (!wasPhone || guide == null) return
        val atPin = guide.step.kind == GuideStep.Kind.Pin
        if (atPin && pinTyped.intValue > 0) pinSent = true
        val closedAt = System.currentTimeMillis()
        goneCheck?.cancel()
        goneCheck = scope.launch {
            delay(if (pinSent) AFTER_PIN_MILLIS else GONE_MILLIS)
            if (!inPhoneBox) GuideSession.end(GuideSession.End.Gone, at = closedAt)
        }
    }

    /** Shown anywhere but bKash's box: hand back to the user's keyboard straight away. */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        registry.currentState = Lifecycle.State.RESUMED
        val inPhone = info.packageName == KeyboardFill.PHONE_PACKAGE
        if (!inPhone || GuideSession.state.value == null) act(KeyMove.HandBack())
    }

    private fun boxEmpty(): Boolean = boxText().isNullOrEmpty()

    private fun act(move: KeyMove) {
        when (move) {
            KeyMove.None -> Unit
            is KeyMove.Type -> {
                currentInputConnection?.commitText(move.text, 1) ?: return
                GuideSession.filled(move.index)
            }
            is KeyMove.HandBack -> {
                move.index?.let(GuideSession::reached)
                switchAway()
            }
            is KeyMove.PinPad -> {
                GuideSession.reached(move.index)
                pinTyped.intValue = 0 // a new, empty PIN box
                pinSent = false
                showPad()
            }
            // bKash's answer is up, in a box of its own: put the pad away and give the keyboard back.
            KeyMove.Finished -> {
                requestHideSelf(0)
                GuideSession.end(GuideSession.End.Answered)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        registry.currentState = Lifecycle.State.STARTED
    }

    override fun onDestroy() {
        // Torn down mid-guide (the user switched keyboards, the system reclaimed it): nothing here can
        // follow bKash any more, so the guide ends rather than wait on a box it will never hear about.
        if (goneCheck?.isActive == true) GuideSession.end(GuideSession.End.Gone)
        goneCheck?.cancel()
        scope.cancel()
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    /**
     * A digit typed on the pad. For a reply the keyboard didn't know, that answers the step; for one it
     * typed, it's a change (see [GuideSession.edited]).
     */
    private fun key(digit: String) {
        if (atPin()) {
            if (pinTyped.intValue >= PIN_DIGITS) return // bKash PINs are 5 digits; a 6th would be refused
            currentInputConnection?.commitText(digit, 1) ?: return
            pinTyped.intValue++
            return
        }
        currentInputConnection?.commitText(digit, 1) ?: return
        noteEdit()
    }

    private fun delete() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
        if (atPin()) pinTyped.intValue = (pinTyped.intValue - 1).coerceAtLeast(0) else noteEdit()
    }

    /**
     * bKash's box takes focus without asking for a keyboard (the replies are typed without one showing),
     * so at the PIN the pad puts itself up rather than waiting for the user to tap the box.
     */
    private fun showPad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requestShowSelf(0)
    }

    private fun atPin() = GuideSession.state.value?.step?.kind == GuideStep.Kind.Pin

    private fun noteEdit() {
        val guide = GuideSession.state.value ?: return
        when {
            guide.step.kind == GuideStep.Kind.Pin -> Unit
            // A different reply where the No to an old session was typed: bKash didn't ask, and the user
            // answered its first menu instead.
            guide.step.kind == GuideStep.Kind.Resume && !boxText().isNullOrEmpty() && boxText() != guide.step.fill ->
                GuideSession.filled(guide.index + 1)
            // Typed back to what Chalaiden had typed: not a change after all.
            guide.step.fill == null || boxText() == guide.step.fill -> GuideSession.filled(guide.index)
            else -> GuideSession.edited()
        }
    }

    private fun boxText(): String? {
        val ic = currentInputConnection ?: return null
        return "${ic.getTextBeforeCursor(64, 0) ?: ""}${ic.getTextAfterCursor(64, 0) ?: ""}"
    }

    /**
     * Back to the keyboard the user had before; failing that, the next one; failing that, the picker
     * (unless [quiet]: nothing is on screen to explain a picker popping up).
     */
    private fun switchAway(quiet: Boolean = false) {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod() || switchToNextInputMethod(false)
        } else {
            val token = window.window?.attributes?.token
            @Suppress("DEPRECATION")
            token != null && getSystemService(InputMethodManager::class.java).switchToLastInputMethod(token)
        }
        if (!switched && !quiet) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }
}

/** Longer than bKash ever takes to send its next prompt after a reply. */
private const val GONE_MILLIS = PhoneQuirks.SLOWEST_REPLY_MILLIS

/**
 * After the PIN box closes with digits in it. bKash's answer is either a new box (handled at once) or an
 * OK-only message; either way the user has nothing left to do on the card. Long enough to ride out the
 * moment the box closes before bKash's "Running USSD code…" takes its place.
 */
private const val AFTER_PIN_MILLIS = 2_500L

private const val PIN_DIGITS = 5

/**
 * A number pad and nothing else: for a menu number bKash put somewhere else, or, at the PIN, for the PIN
 * itself (with dots, so the digits never show on the keyboard). The card above bKash says what to do.
 * No pad outside a guide. [bottom] keeps the last row clear of the system's bar.
 */
@Composable
private fun Panel(pinTyped: Int, bottom: Dp, onKey: (String) -> Unit, onDelete: () -> Unit) {
    val t = tokens
    val haptics = LocalHapticFeedback.current
    val guide by GuideSession.state.collectAsState()
    val step = guide?.step ?: return
    val pin = step.kind == GuideStep.Kind.Pin
    val tap = { k: String -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(k) }
    Column(
        Modifier.fillMaxWidth().background(t.bg).padding(bottom = bottom).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (pin) {
            PinDots(pinTyped, PIN_DIGITS, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 10.dp))
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { Key(it) { tap(it) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f))
            Key("0") { tap("0") }
            Key(null) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onDelete() }
        }
    }
}

@Composable
private fun RowScope.Key(label: String?, onClick: () -> Unit) {
    val t = tokens
    Box(
        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(14.dp)).background(t.surface)
            .clickable(role = Role.Button, onClickLabel = label ?: "Delete", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, style = Type.title.copy(fontSize = 22.sp), color = t.ink)
        else Icon(AppIcons.Backspace, "Delete", tint = t.ink, modifier = Modifier.size(22.dp))
    }
}

/** Turning the keyboard on and picking it, from the app's side. */
object ChalaidenKeyboard {
    private fun id(context: Context) = ComponentName(context, KeyboardService::class.java)

    /** Switched on in Settings → Keyboards. */
    fun isEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.component == id(context) }
    }.getOrDefault(false)

    /** The keyboard in use right now. */
    fun isCurrent(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(InputMethodManager::class.java).currentInputMethodInfo?.component == id(context)
        } else {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { ComponentName.unflattenFromString(it) } == id(context)
        }
    }.getOrDefault(false)

    fun openSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Android's keyboard picker. Only works from a window that has focus. */
    fun pick(context: Context): Boolean = runCatching {
        context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        true
    }.getOrDefault(false)
}

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
 * the user only checks it and taps Send. It works only there: anywhere else, and for the PIN, it
 * switches straight back to the user's own keyboard. It keeps nothing it types.
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
                    guide == null -> if (before != null) switchAway(quiet = true)
                    // Next on the card: the reply in bKash's box is for the step the user skipped.
                    before != null && guide.index != before.index && guide.filled != guide.index && inPhoneBox -> follow(guide)
                }
            }
        }
    }

    /** Puts the card's new step's reply in bKash's box in place of the old one; none for the PIN. */
    private fun follow(guide: GuideSession.State) {
        val step = guide.step
        if (step.kind == GuideStep.Kind.Pin) {
            replaceBox("")
            return switchAway()
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
            setContent { ChalaidenTheme { Panel(onKey = ::key, onDelete = ::delete, onSwitch = ::switchAway) } }
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
        act(KeyboardFill.decide(GuideSession.state.value, inPhone = true, empty = boxEmpty()))
    }

    /**
     * bKash's box closed: Send (the next prompt follows within seconds), or the dialog went away for good
     * (Cancel, bKash timing out) while Chalaiden isn't in front to notice. With no new box for
     * [GONE_MILLIS], the guide is over, the same as when Chalaiden comes back to the front.
     */
    override fun onFinishInput() {
        val wasPhone = inPhoneBox
        inPhoneBox = false
        super.onFinishInput()
        val guide = GuideSession.state.value
        if (!wasPhone || guide == null || guide.step.kind == GuideStep.Kind.Pin) return
        val closedAt = System.currentTimeMillis()
        goneCheck?.cancel()
        goneCheck = scope.launch {
            delay(GONE_MILLIS)
            if (!inPhoneBox) GuideSession.end(GuideSession.End.Gone, at = closedAt)
        }
    }

    /** Shown anywhere but bKash's box: hand back to the user's keyboard straight away. */
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        registry.currentState = Lifecycle.State.RESUMED
        val inPhone = info.packageName == KeyboardFill.PHONE_PACKAGE
        if (!inPhone || GuideSession.state.value == null) act(KeyMove.HandBack())
        else if (GuideSession.state.value?.step?.kind == GuideStep.Kind.Pin) switchAway()
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
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        registry.currentState = Lifecycle.State.STARTED
    }

    override fun onDestroy() {
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
        currentInputConnection?.commitText(digit, 1) ?: return
        noteEdit()
    }

    private fun delete() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
        noteEdit()
    }

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

/** Longer than bKash ever takes to send its next prompt after a reply (seconds, on a slow network). */
private const val GONE_MILLIS = 12_000L

/** Status line, then a number pad (for a menu number bKash put somewhere else). No pad for the PIN. */
@Composable
private fun Panel(onKey: (String) -> Unit, onDelete: () -> Unit, onSwitch: () -> Unit) {
    val t = tokens
    val guide by GuideSession.state.collectAsState()
    val step = guide?.step
    val pin = step == null || step.kind == GuideStep.Kind.Pin
    Column(
        Modifier.fillMaxWidth().background(t.bg).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        step == null -> "Chalaiden keyboard"
                        pin -> "Your PIN goes on your own keyboard"
                        guide?.filled == guide?.index && step.fill != null -> "Typed ${step.fill} · ${step.title.removePrefix("Choose ").removePrefix("Type the ")}"
                        else -> step.title
                    },
                    style = Type.title, color = t.ink, maxLines = 1,
                )
                Text(
                    when {
                        step == null -> "It only types bKash replies."
                        pin -> "Tap Your keyboard."
                        else -> "Check it, then tap Send."
                    },
                    style = Type.label, color = t.inkSoft, maxLines = 1,
                )
            }
            Text(
                "Your keyboard", style = Type.button, color = t.accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onSwitch)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        if (!pin) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { Key(it) { onKey(it) } } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f))
                Key("0") { onKey("0") }
                Key(null, onDelete)
            }
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

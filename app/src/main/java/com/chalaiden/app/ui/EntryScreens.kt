package com.chalaiden.app.ui

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import android.widget.Toast
import com.chalaiden.app.ui.components.BlinkingCaret
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalDensity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ocr.NumberExtractor
import com.chalaiden.app.ui.components.NumberRail
import com.chalaiden.app.ui.components.PhoneGroups
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.Chip
import com.chalaiden.app.ui.components.Keypad
import com.chalaiden.app.ui.components.capScale
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.components.HoldToConfirm
import com.chalaiden.app.ui.components.TextAction
import com.chalaiden.app.ui.components.TopBar
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.FlowKind
import com.chalaiden.app.ussd.Flows

/**
 * Confirm or type the recipient number. When it came from the camera, the crop sits on top
 * so the user can compare it digit by digit with what was read.
 */
@Composable
fun NumberScreen(
    kind: FlowKind,
    initial: String,
    crop: Bitmap?,
    groups: List<Int>?,
    onScan: (() -> Unit)?,
    onContinue: (number: String, reference: String) -> Unit,
    onBack: () -> Unit,
) {
    val t = tokens
    val context = LocalContext.current
    var number by rememberSaveable { mutableStateOf(initial.filter(Char::isDigit).take(11)) }
    // Asked here, not with the amount: bKash's menu is walked from this screen's Continue, and the
    // reference is one of its steps. bKash's cash out menu has none.
    val takesReference = kind == FlowKind.Payment || kind == FlowKind.SendMoney
    var reference by rememberSaveable { mutableStateOf("") }
    var showReference by rememberSaveable { mutableStateOf(false) }
    val contact = rememberLauncherForActivityResult(PickPhone()) { picked ->
        picked?.let { phoneFromContactUri(context, Uri.parse(it)) }
            ?.let { NumberExtractor.find(it).firstOrNull() }
            ?.let { number = it }
    }
    val valid = NumberExtractor.isValid(number)
    @OptIn(ExperimentalLayoutApi::class) val typing = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding().imePadding()) {
        TopBar(
            when (kind) {
                FlowKind.Payment -> "Shop's bKash number"
                FlowKind.CashOut -> "Agent's bKash number"
                else -> "Send money"
            },
            onBack,
        )
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            // The photo gives way while the keyboard is up, so the reference field keeps its room.
            if (crop != null && !typing) {
                Spacer(Modifier.height(8.dp))
                Text("From the card", style = Type.label, color = t.inkSoft)
                Spacer(Modifier.height(6.dp))
                Image(
                    crop.asImageBitmap(),
                    contentDescription = "Photo of the number on the card",
                    contentScale = ContentScale.Fit,
                    // Number strips are very wide; cap the ratio so the digits stay big enough to compare.
                    modifier = Modifier.fillMaxWidth()
                        .aspectRatio((crop.width.toFloat() / crop.height).coerceIn(1.5f, 6f))
                        .clip(RoundedCornerShape(20.dp)).background(t.surface),
                )
                Spacer(Modifier.height(14.dp))
                Text(if (initial.isNotEmpty()) "Number found" else "Type the number you see", style = Type.label, color = t.inkSoft)
            } else {
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            // Grouped the way the card prints it, so the two read alike digit by digit. Long-press opens the
            // phone's own text menu with Paste, as a text field does; TalkBack gets Paste as an action.
            val toolbar = LocalTextToolbar.current
            val haptics = LocalHapticFeedback.current
            var railBounds by remember { mutableStateOf(Rect.Zero) }
            fun paste() {
                toolbar.hide()
                pasted(context)?.let { number = it }
                    ?: Toast.makeText(context, "No phone number in what you copied.", Toast.LENGTH_SHORT).show()
            }
            NumberRail(
                number, groups = groups ?: PhoneGroups, cursor = number.length < 11,
                modifier = Modifier
                    .onGloballyPositioned { railBounds = it.boundsInRoot() }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { toolbar.hide() },
                            onLongPress = {
                                if (context.getSystemService(ClipboardManager::class.java).hasPrimaryClip()) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    toolbar.showMenu(railBounds, onCopyRequested = null, onPasteRequested = ::paste, onCutRequested = null, onSelectAllRequested = null)
                                }
                            },
                        )
                    }
                    .semantics { customActions = listOf(CustomAccessibilityAction("Paste") { paste(); true }) },
            )
            // Only when there is something to say; otherwise the chips sit right under the number.
            val note = when {
                valid -> if (crop != null) "Check every digit against the photo." else null
                number.length == 11 -> "That isn't a mobile number. It should start with 013 to 019."
                else -> null
            }
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(note, style = Type.label, color = if (valid) t.inkSoft else t.error)
            }
            if (crop == null) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Chip(AppIcons.Person, "Contacts") { contact.launch(Unit) }
                    if (onScan != null) Chip(AppIcons.Scan, "Scan") { onScan() }
                }
            }
            // The note's room, kept below the chips when there's no note: the chips move up to the number,
            // and Add reference (or its field) stays where it was.
            if (takesReference && note == null) {
                Spacer(Modifier.height(10.dp + with(LocalDensity.current) { Type.label.lineHeight.toDp() }))
            }
            if (takesReference && !showReference) {
                Text(
                    "Add reference", style = Type.button, color = t.accent,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { showReference = true }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                )
            }
            if (takesReference && showReference) {
                val focus = remember { FocusRequester() }
                // Opened by the chip: straight into typing. Coming back to the screen leaves it be.
                var opened by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) { if (!opened) { opened = true; focus.requestFocus() } }
                BasicTextField(
                    value = reference,
                    onValueChange = { reference = Flows.cleanReference(it) },
                    singleLine = true,
                    textStyle = Type.body.copy(color = t.ink),
                    cursorBrush = SolidColor(t.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(focus)
                        .clip(RoundedCornerShape(16.dp)).background(t.surface).padding(16.dp),
                    decorationBox = { inner ->
                        if (reference.isEmpty()) Text("Reference, e.g. table 4", style = Type.body, color = t.inkSoft)
                        inner()
                    },
                )
            }
            // While the phone's keyboard types the reference it covers the keypad anyway; the room goes to it.
            if (typing) {
                Spacer(Modifier.weight(1f))
            } else {
                Keypad(
                    onKey = { if (number.length < 11) number += it },
                    onBackspace = { number = number.dropLast(1) },
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
            }
        }
        PrimaryButton("Continue", enabled = valid, modifier = Modifier.padding(20.dp)) {
            // The keyboard goes with this screen, not over the next one's keypad.
            focus.clearFocus()
            keyboard?.hide()
            onContinue(number, reference.trim())
        }
    }
}

/**
 * The amount. [amount] is held by the caller: on the run screen the activity and the cover both draw this,
 * and digits typed as the cover takes over must carry across (see [AmountEntry]).
 */
@Composable
fun AmountScreen(
    kind: FlowKind,
    number: String,
    groups: List<Int>?,
    entry: MutableState<String>,
    onPay: (amount: String) -> Unit,
    onBack: () -> Unit,
    /** Leaves the payment altogether (back goes one step, to the number). */
    onCancel: () -> Unit,
    hold: Boolean = false,
) {
    val t = tokens
    // Read live in the handlers, not captured: a key pressed before the next frame must see the last one.
    val amount = entry.value
    val value = amount.toLongOrNull() ?: 0
    val verb = when (kind) {
        FlowKind.Payment -> "Pay"
        FlowKind.CashOut -> "Cash out"
        else -> "Send"
    }

    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        TopBar(
            when (kind) {
                FlowKind.Payment -> "Pay shop"
                FlowKind.CashOut -> "Cash out"
                else -> "Send money"
            },
            onBack,
        )
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            // Settled now: the compact line the PIN screen shows next, so the amount is the one hero here and
            // the number carries over unchanged into the run. Tap it to go back and change it.
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "Change number", onClick = onBack)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (kind) {
                            FlowKind.Payment -> "To shop"
                            FlowKind.CashOut -> "To agent"
                            else -> "To"
                        },
                        style = Type.label, color = t.inkSoft,
                    )
                    Spacer(Modifier.height(2.dp))
                    NumberRail(number, groups = groups ?: PhoneGroups, compact = true)
                }
                // The one way to fix a wrong payee before money moves: a button, not a footnote.
                Box(
                    Modifier.padding(start = 12.dp).heightIn(min = 36.dp).clip(RoundedCornerShape(50)).background(t.raised)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Change", style = Type.label.copy(fontWeight = FontWeight.Medium), color = t.ink)
                }
            }
            // The amount sits centred just above the keypad that types it; the pair hugs the bottom, and only
            // when space runs short (200% text, a small phone) do the keys give up height.
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val shown = if (amount.isEmpty()) "0" else "%,d".format(value)
                val amountSize = (if (shown.length > 6) 64.sp else 76.sp).capScale()
                // The keypad types here, so it gets a caret like any field, always after the figures (the grey 0
                // too, while empty).
                // Measured on the amount face: figures stand 0.75 of the size tall, on a baseline 0.29 above the line's foot.
                val caretHeight = with(LocalDensity.current) { amountSize.toDp() } * 0.75f
                val caret = @Composable {
                    key(amount) {
                        BlinkingCaret(caretHeight, Modifier.padding(horizontal = 4.dp).padding(bottom = caretHeight * 0.39f), width = 3.dp)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        shown,
                        style = Type.amount.copy(fontSize = amountSize, letterSpacing = (-2).sp),
                        color = if (amount.isEmpty()) t.line else t.ink,
                        maxLines = 1,
                    )
                    caret()
                    Text("TK", style = Type.title.copy(fontSize = 26.sp.capScale()), color = t.inkSoft,
                        modifier = Modifier.padding(start = 10.dp, bottom = 16.dp))
                }
            }
            Keypad(
                onKey = { val a = entry.value; if (a.length < 6 && !(a.isEmpty() && it == '0')) entry.value = a + it },
                onBackspace = { entry.value = entry.value.dropLast(1) },
                modifier = Modifier.weight(1f, fill = false).padding(top = 28.dp),
            )
            }
        }
        if (hold) {
            // One Tap's fingerprint already unlocked the PIN: no PIN screen follows, so this is where money
            // moves, and it takes a hold, as "Hold to pay" does.
            HoldToConfirm(
                if (value > 0) "Hold to ${verb.lowercase()} ${"%,d".format(value)} TK" else "Hold to ${verb.lowercase()}",
                enabled = value > 0,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
            ) { onPay(entry.value) }
        } else {
            PrimaryButton(
                if (value > 0) "$verb ${"%,d".format(value)} TK" else verb,
                enabled = value > 0,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
            ) { onPay(entry.value) }
        }
        // As on the PIN screen: under the button, where the thumb already is.
        TextAction("Cancel", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 12.dp), onClick = onCancel)
    }
}

private fun pasted(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
    return (0 until clip.itemCount).firstNotNullOfOrNull {
        NumberExtractor.find(clip.getItemAt(it).coerceToText(context).toString()).firstOrNull()
    }
}

/** System contact picker for phone numbers; grants one-off read access, no contacts permission. */
private class PickPhone : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): String? = intent?.data?.toString()
}

private fun phoneFromContactUri(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

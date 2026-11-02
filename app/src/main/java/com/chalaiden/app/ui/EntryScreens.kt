package com.chalaiden.app.ui

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ocr.NumberExtractor
import com.chalaiden.app.ui.components.NumberRail
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.Chip
import com.chalaiden.app.ui.components.Keypad
import com.chalaiden.app.ui.components.PrimaryButton
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
    onScan: (() -> Unit)?,
    onContinue: (String) -> Unit,
    onBack: () -> Unit,
) {
    val t = tokens
    val context = LocalContext.current
    var number by remember { mutableStateOf(initial.filter(Char::isDigit).take(11)) }
    val contact = rememberLauncherForActivityResult(PickPhone()) { picked ->
        picked?.let { phoneFromContactUri(context, Uri.parse(it)) }
            ?.let { NumberExtractor.find(it).firstOrNull() }
            ?.let { number = it }
    }
    val valid = NumberExtractor.isValid(number)

    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        TopBar(if (kind == FlowKind.Payment) "Shop's bKash number" else "Send to", onBack)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            if (crop != null) {
                Spacer(Modifier.height(8.dp))
                Text("From the card", style = Type.label, color = t.inkSoft)
                Spacer(Modifier.height(6.dp))
                Image(
                    crop.asImageBitmap(),
                    contentDescription = "Photo of the number on the card",
                    contentScale = ContentScale.Fit,
                    // Number strips are very wide; cap the ratio so the digits stay big enough to compare.
                    modifier = Modifier.fillMaxWidth()
                        .aspectRatio((crop.width.toFloat() / crop.height).coerceIn(1.5f, 4.2f))
                        .clip(RoundedCornerShape(20.dp)).background(t.surface),
                )
                Spacer(Modifier.height(14.dp))
                Text(if (initial.isNotEmpty()) "Chalaiden read" else "Type the number you see", style = Type.label, color = t.inkSoft)
            } else {
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            NumberRail(number, cursor = number.length < 11)
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    valid -> if (crop != null) "Check every digit against the photo." else " "
                    number.length == 11 -> "That isn't a mobile number. It should start with 013 to 019."
                    else -> "${11 - number.length} more digit${if (11 - number.length == 1) "" else "s"}"
                },
                style = Type.label,
                color = if (number.length == 11 && !valid) t.error else t.inkSoft,
            )
            if (crop == null) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (kind == FlowKind.SendMoney) Chip(AppIcons.Person, "Contacts") { contact.launch(Unit) }
                    Chip(AppIcons.Paste, "Paste") { pasted(context)?.let { number = it } }
                    if (onScan != null) Chip(AppIcons.Scan, "Scan") { onScan() }
                }
            }
            Spacer(Modifier.weight(1f))
            Keypad(onKey = { if (number.length < 11) number += it }, onBackspace = { number = number.dropLast(1) })
        }
        PrimaryButton("Continue", enabled = valid, modifier = Modifier.padding(20.dp)) { onContinue(number) }
    }
}

/** Amount plus an optional reference. Starts the bKash session on continue. */
@Composable
fun AmountScreen(kind: FlowKind, number: String, onPay: (amount: String, reference: String) -> Unit, onBack: () -> Unit) {
    val t = tokens
    var amount by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var showReference by remember { mutableStateOf(false) }
    val value = amount.toLongOrNull() ?: 0
    val verb = if (kind == FlowKind.Payment) "Pay" else "Send"

    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding().imePadding()) {
        TopBar(if (kind == FlowKind.Payment) "Pay shop" else "Send money", onBack)
        Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(4.dp))
            NumberRail(number, compact = true)
            Spacer(Modifier.height(28.dp))
            Text("Amount", style = Type.label, color = t.inkSoft)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Tk ", style = Type.title, color = t.inkSoft, modifier = Modifier.padding(bottom = 10.dp))
                Text(
                    if (amount.isEmpty()) "0" else "%,d".format(value),
                    style = Type.amount,
                    color = if (amount.isEmpty()) t.line else t.ink,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (showReference) {
                BasicTextField(
                    value = reference,
                    onValueChange = { reference = Flows.cleanReference(it) },
                    singleLine = true,
                    textStyle = Type.body.copy(color = t.ink),
                    cursorBrush = SolidColor(t.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.surface)
                        .padding(16.dp),
                    decorationBox = { inner ->
                        if (reference.isEmpty()) Text("Reference, e.g. table 4", style = Type.body, color = t.inkSoft)
                        inner()
                    },
                )
            } else {
                Text("Add reference", style = Type.button, color = t.accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showReference = true }.padding(vertical = 10.dp))
            }
            Spacer(Modifier.weight(1f))
            Keypad(
                onKey = { if (amount.length < 6 && !(amount.isEmpty() && it == '0')) amount += it },
                onBackspace = { amount = amount.dropLast(1) },
            )
        }
        PrimaryButton(
            if (value > 0) "$verb Tk ${"%,d".format(value)}" else verb,
            enabled = value > 0,
            modifier = Modifier.padding(20.dp),
        ) { onPay(amount, reference.trim()) }
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

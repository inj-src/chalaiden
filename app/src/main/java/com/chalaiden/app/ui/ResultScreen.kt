package com.chalaiden.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.chalaiden.app.R
import com.chalaiden.app.ui.components.NumberRail
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.PopIn
import com.chalaiden.app.ui.components.SuccessBadge
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.components.TextAction
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.FlowKind
import com.chalaiden.app.ussd.Flows
import com.chalaiden.app.ussd.Outcome
import com.chalaiden.app.ussd.Prompt
import com.chalaiden.app.ussd.UssdFlow
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** bKash seems to refuse a new session that starts within ~30 s of the last one. */
private const val RETRY_COOLDOWN_SECONDS = 30

@Composable
fun ResultScreen(flow: UssdFlow, outcome: Outcome, onRetry: () -> Unit, onCheckBalance: () -> Unit, onDone: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val time = remember { Date() }
    var cooldown by remember { mutableIntStateOf(RETRY_COOLDOWN_SECONDS) }
    LaunchedEffect(Unit) { while (cooldown > 0) { delay(1000); cooldown-- } }

    val (title, note) = describe(flow, outcome)
    val good = outcome is Outcome.Success
    val canRetry = outcome is Outcome.Cancelled || (outcome is Outcome.Failed && !outcome.pinSent)

    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding().padding(24.dp)) {
        val fg = t.ink
        val soft = t.inkSoft
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(32.dp))
            if (outcome is Outcome.Success) {
                SuccessBadge(t.accent, t.onAccent, 64.dp) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            } else {
                PopIn {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(if (outcome is Outcome.Unknown) t.accent else t.raised),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (outcome is Outcome.Unknown) AppIcons.Alert else AppIcons.Cross,
                            contentDescription = null,
                            tint = if (outcome is Outcome.Unknown) t.onAccent else t.ink,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(title, style = Type.display, color = fg)

            val balances = (outcome as? Outcome.Success)?.takeIf { flow.kind == FlowKind.Balance }
                ?.let { Prompt.balances(it.message) }.orEmpty()
            val bigAmount = when {
                flow.kind == FlowKind.Balance -> balances["current"] ?: balances["available"]
                flow.amount != null && outcome !is Outcome.Cancelled -> "%,d".format(flow.amount.toLongOrNull() ?: 0)
                else -> null
            }
            bigAmount?.let { Text("Tk $it", style = Type.amount, color = fg) }
            flow.recipient?.let {
                Spacer(Modifier.height(12.dp))
                NumberRail(it, compact = true)
            }
            Spacer(Modifier.height(20.dp))
            if (outcome is Outcome.Success && outcome.trxId != null) {
                Detail("TrxID", outcome.trxId, fg, soft)
            }
            balances["available"]?.takeIf { it != balances["current"] }?.let { Detail("Available", "Tk $it", fg, soft) }
            flow.reference?.takeUnless(Flows::isDefaultReference)?.let { Detail("Reference", it, fg, soft) }
            Detail("Time", SimpleDateFormat("h:mm a", Locale.ENGLISH).format(time), fg, soft)
            Detail("Date", SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(time), fg, soft)
            if (note != null) {
                Spacer(Modifier.height(16.dp))
                Text(note, style = Type.body, color = fg)
            }
            // bKash's own words matter when something went wrong; on a parsed balance they'd only repeat it.
            val raw = when (outcome) {
                is Outcome.Success -> outcome.message.takeIf { balances.isEmpty() }
                is Outcome.Failed -> outcome.message
                is Outcome.Unknown -> outcome.message
                Outcome.Cancelled -> null
            }
            if (!raw.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("bKash said", style = Type.label, color = soft)
                Text(raw, style = Type.body, color = soft)
            }
        }

        if (outcome is Outcome.Unknown) {
            PrimaryButton("Check balance", modifier = Modifier.padding(bottom = 8.dp), onClick = onCheckBalance)
        }
        if (canRetry) {
            PrimaryButton(
                if (cooldown > 0) "Try again in $cooldown s" else "Try again",
                enabled = cooldown == 0,
                modifier = Modifier.padding(bottom = 4.dp),
                onClick = onRetry,
            )
        } else if (outcome !is Outcome.Unknown) {
            PrimaryButton("Done", modifier = Modifier.padding(bottom = 4.dp), onClick = onDone)
        }
        if (good && flow.kind != FlowKind.Balance) {
            TextAction("Share receipt", Modifier.align(Alignment.CenterHorizontally)) {
                shareReceipt(context, flow, outcome as Outcome.Success, time)
            }
        } else if (canRetry || outcome is Outcome.Unknown) {
            TextAction("Done", Modifier.align(Alignment.CenterHorizontally), onClick = onDone)
        }
    }
}

@Composable
private fun Detail(label: String, value: String, fg: androidx.compose.ui.graphics.Color, soft: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = Type.body, color = soft, modifier = Modifier.weight(1f))
        Text(value, style = Type.body.copy(fontFamily = Type.button.fontFamily), color = fg)
    }
}

private fun describe(flow: UssdFlow, outcome: Outcome): Pair<String, String?> {
    val done = when (flow.kind) {
        FlowKind.Payment -> "Paid"
        FlowKind.SendMoney -> "Sent"
        FlowKind.Balance -> "Your balance"
    }
    return when (outcome) {
        is Outcome.Success -> done to if (flow.kind == FlowKind.Payment) "Show this screen to the shop." else null
        is Outcome.Failed -> if (outcome.pinSent) {
            "Not completed" to "bKash turned this down after your PIN. Read its message below before trying again."
        } else if (outcome.menuMissing) {
            "bKash was still busy" to "Nothing was charged. bKash was finishing your last request. Wait a moment, then try again."
        } else {
            val expired = outcome.message.contains("Connection problem", ignoreCase = true)
            (if (expired) "bKash closed the session" else "Not sent to bKash") to
                "Nothing was charged. " + if (expired) "It waits about 3 minutes for your PIN." else "Check the number and try again."
        }
        is Outcome.Unknown -> "Check before paying again" to
            "Chalaiden couldn't tell if this went through. Check your balance before you pay again."
        Outcome.Cancelled -> "Cancelled" to "Nothing was charged."
    }
}

/** Draws a plain receipt image and opens the share sheet. Nothing is kept after sharing. */
private fun shareReceipt(context: Context, flow: UssdFlow, outcome: Outcome.Success, time: Date) {
    val w = 1080
    val h = 1240
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(0xFFF5F8F7.toInt())
    val font = ResourcesCompat.getFont(context, R.font.geist) ?: Typeface.DEFAULT
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F2E2B.toInt(); typeface = font }
    val soft = Paint(ink).apply { color = 0xFF52706C.toInt() }
    val teal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E5E57.toInt() }
    c.drawRect(0f, 0f, w.toFloat(), 220f, teal)
    ink.textSize = 56f
    c.drawText("chalaiden", 80f, 135f, Paint(ink).apply { color = 0xFFF2FBF9.toInt(); isFakeBoldText = true })
    var y = 340f
    soft.textSize = 40f
    c.drawText(if (flow.kind == FlowKind.Payment) "Payment" else "Send Money", 80f, y, soft)
    y += 120f
    ink.textSize = 110f
    c.drawText("Tk ${"%,d".format(flow.amount?.toLongOrNull() ?: 0)}", 80f, y, Paint(ink).apply { isFakeBoldText = true })
    y += 110f
    ink.textSize = 44f
    listOf(
        "To" to flow.recipient.orEmpty(),
        "TrxID" to (outcome.trxId ?: "-"),
        "Reference" to flow.reference?.takeUnless(Flows::isDefaultReference),
        "Time" to SimpleDateFormat("h:mm a", Locale.ENGLISH).format(time),
        "Date" to SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(time),
    ).forEach { (k, v) ->
        if (v == null) return@forEach
        y += 90f
        c.drawText(k, 80f, y, soft)
        c.drawText(v, w - 80f - ink.measureText(v), y, ink)
    }
    soft.textSize = 32f
    c.drawText("Paid through bKash *247# with Chalaiden.", 80f, h - 90f, soft)

    val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
    val file = File(dir, "receipt.png")
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share receipt"))
}

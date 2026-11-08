package com.chalaiden.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.BiasAlignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.chalaiden.app.ui.components.AmountText
import com.chalaiden.app.ui.components.DetailRow
import com.chalaiden.app.ui.components.NumberRail
import com.chalaiden.app.ui.components.PhoneGroups
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

    // Same frame as the run screens (24dp sides, 20dp top and bottom), so the main button lands where
    // "Hold to pay" was.
    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)) {
        val fg = t.ink
        val soft = t.inkSoft
        // The result sits a little above the middle of the space over the buttons, not hung from the top
        // with a dead band under it. Scrolls when it's taller than that space (long bKash replies, big text).
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val room = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().heightIn(min = room), contentAlignment = BiasAlignment(-1f, -0.3f)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            OutcomeBadge(flow, outcome) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            Spacer(Modifier.height(20.dp))
            Text(title, style = Type.display, color = fg)

            val balances = (outcome as? Outcome.Success)?.takeIf { flow.kind == FlowKind.Balance }
                ?.let { Prompt.balances(it.message) }.orEmpty()
            val amount = flow.amount?.let { "%,d".format(it.toLongOrNull() ?: 0) }
            // Only money that moved gets the big figure. Otherwise the note leads (what to do now), then
            // bKash's own reason, then the amount as one quiet line, so a glance can't read it as paid.
            if (outcome is Outcome.Success) {
                // What to do with this screen, right under what happened, where 11-14 put their note.
                note?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = Type.body, color = soft)
                }
                when {
                    flow.kind == FlowKind.Balance -> (balances["current"] ?: balances["available"])?.let { AmountText(it) }
                    amount != null -> AmountText(amount)
                }
            } else {
                note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = Type.body, color = soft)
                }
                reason(outcome)?.let { (caption, words) ->
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.surface).padding(12.dp)) {
                        Text(caption, style = Type.label, color = soft)
                        Text(words, style = Type.body, color = fg)
                    }
                }
                amount?.let {
                    Spacer(Modifier.height(20.dp))
                    Text("$it TK, ${if (outcome is Outcome.Unknown) "status unknown" else "not ${notDone(flow.kind)}"}", style = Type.title, color = soft)
                }
            }
            flow.recipient?.let {
                Spacer(Modifier.height(if (outcome is Outcome.Success) 16.dp else 12.dp))
                Text(if (flow.kind == FlowKind.Payment) "To shop" else if (flow.kind == FlowKind.CashOut) "To agent" else "To",
                    style = Type.label, color = soft)
                Spacer(Modifier.height(2.dp))
                NumberRail(it, groups = flow.groups ?: PhoneGroups, compact = true)
            }
            Spacer(Modifier.height(20.dp))
            if (outcome is Outcome.Success && outcome.trxId != null) {
                DetailRow("TrxID", outcome.trxId, Modifier.padding(vertical = 4.dp))
            }
            balances["available"]?.takeIf { it != balances["current"] }?.let { DetailRow("Available", "$it TK", Modifier.padding(vertical = 4.dp)) }
            flow.reference?.takeUnless(Flows::isDefaultReference)?.let { DetailRow("Reference", it, Modifier.padding(vertical = 4.dp)) }
            DetailRow("Time", SimpleDateFormat("h:mm a", Locale.ENGLISH).format(time), Modifier.padding(vertical = 4.dp))
            DetailRow("Date", SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(time), Modifier.padding(vertical = 4.dp))
            if (outcome is Outcome.Success) {
                // A reply bKash sent that Chalaiden couldn't read as a balance or a TrxID: show it as sent.
                outcome.message.takeIf { balances.isEmpty() && outcome.trxId == null && it.isNotBlank() }?.let {
                    Spacer(Modifier.height(16.dp))
                    Text("bKash said", style = Type.label, color = soft)
                    Text(it, style = Type.body, color = soft)
                }
            }
        }
        }
        }
        }

        // The bottom is the same on every result: the main button, then a 48dp slot for a second action
        // (left empty when there is none), so the thumb finds them where it found "Hold to pay". Retry
        // keeps its place throughout: it fills up while bKash would still refuse it, then turns solid.
        when {
            outcome is Outcome.Unknown -> PrimaryButton("Check balance", onClick = onCheckBalance)
            canRetry -> RetryButton(cooldown, RETRY_COOLDOWN_SECONDS, onRetry)
            else -> PrimaryButton("Done", onClick = onDone)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(48.dp), contentAlignment = Alignment.Center) {
            when {
                good && flow.kind != FlowKind.Balance -> TextAction("Share receipt") {
                    shareReceipt(context, flow, outcome as Outcome.Success, time)
                }
                canRetry || outcome is Outcome.Unknown -> TextAction("Done", onClick = onDone)
            }
        }
    }
}

/**
 * "Try again", held back for [left] more seconds because bKash refuses a new session that soon. Until then
 * it is an outline filling up toward ready, with the time in its label: waiting, not dead. Then a solid
 * primary button in the same place.
 */
@Composable
private fun RetryButton(left: Int, total: Int, onRetry: () -> Unit) {
    val t = tokens
    Crossfade(left == 0, animationSpec = tween(200), label = "retry-ready") { ready ->
        if (ready) return@Crossfade PrimaryButton("Try again", onClick = onRetry)
        val progress by animateFloatAsState(1f - left / total.toFloat(), tween(1000, easing = LinearEasing), label = "retry")
        // Soft label and a visible sweep: it reads as waiting, not as a button to press now.
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(50)).border(1.dp, t.line, RoundedCornerShape(50))
                .semantics { contentDescription = "Try again, available in $left seconds" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress).background(t.raised))
            Text("Try again in 0:%02d".format(left), style = Type.button, color = t.inkSoft)
        }
    }
}

/**
 * bKash's own words, when they add something to the title. Skipped where Chalaiden has already said what
 * happened in plainer words and bKash's text would contradict it ("Please select a valid option" for busy).
 */
private fun reason(outcome: Outcome): Pair<String, String>? = when (outcome) {
    is Outcome.Failed -> outcome.message.takeUnless {
        it.isBlank() || outcome.menuMissing || it.contains("Connection problem", ignoreCase = true)
    }?.let { (if (outcome.fromBkash) "bKash said" else "What happened") to it }
    // Either bKash's last reply or Chalaiden's note on how the session ended, so not "bKash said".
    is Outcome.Unknown -> outcome.message.takeIf { it.isNotBlank() }?.let { "What we know" to it }
    else -> null
}

/**
 * One look per meaning, so the badge alone tells them apart: a blue tick only when money moved; amber "!"
 * when it may have; red × when bKash refused after the PIN; a grey × when nothing was sent; a clock when
 * bKash was busy; a taka sign for a balance. Only the payment springs; the rest settle without a bounce.
 */
@Composable
private fun OutcomeBadge(flow: UssdFlow, outcome: Outcome, onPaid: () -> Unit) {
    val t = tokens
    if (outcome is Outcome.Success && flow.kind != FlowKind.Balance) {
        SuccessBadge(t.accent, t.onAccent, 64.dp, onShown = onPaid)
        return
    }
    val (icon, disc, glyph) = when {
        outcome is Outcome.Success -> Triple(AppIcons.Taka, t.raised, t.ink)
        outcome is Outcome.Unknown -> Triple(AppIcons.Alert, t.warnSoft, t.warn)
        outcome is Outcome.Failed && outcome.pinSent -> Triple(AppIcons.Cross, t.errorSoft, t.error)
        outcome is Outcome.Failed && outcome.menuMissing -> Triple(AppIcons.Clock, t.raised, t.ink)
        // Stopped before the PIN; the ✕ is kept for the user's own Cancel.
        outcome is Outcome.Failed -> Triple(AppIcons.Block, t.raised, t.ink)
        else -> Triple(AppIcons.Cross, t.raised, t.ink)
    }
    PopIn {
        Box(Modifier.size(64.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(if (icon == AppIcons.Cross || icon == AppIcons.Alert) 34.dp else 30.dp))
        }
    }
}

private fun notDone(kind: FlowKind) = when (kind) {
    FlowKind.Payment -> "paid"
    FlowKind.CashOut -> "cashed out"
    else -> "sent"
}

private fun describe(flow: UssdFlow, outcome: Outcome): Pair<String, String?> {
    val done = when (flow.kind) {
        FlowKind.Payment -> "Paid"
        FlowKind.SendMoney -> "Sent"
        FlowKind.CashOut -> "Cashed out"
        FlowKind.Balance -> "Your balance"
    }
    return when (outcome) {
        is Outcome.Success -> done to when (flow.kind) {
            FlowKind.Payment -> "Show this screen to the shop."
            FlowKind.CashOut -> "Show this screen to the agent."
            else -> null
        }
        is Outcome.Failed -> if (outcome.pinSent) {
            "Not completed" to "bKash turned this down after your PIN. Read its reason before trying again."
        } else if (outcome.menuMissing) {
            "bKash was still busy" to "Nothing was charged. bKash was finishing your last request. Wait a moment, then try again."
        } else {
            val expired = outcome.message.contains("Connection problem", ignoreCase = true)
            (if (expired) "bKash closed the session" else "Not sent") to
                "Nothing was charged. " + if (expired) "It waits about 3 minutes for your PIN." else "It stopped before your PIN."
        }
        is Outcome.Unknown -> "Check your balance" to
            "Chalaiden couldn't tell if this went through. Check your balance before you pay again."
        Outcome.Cancelled -> "Cancelled" to "Nothing was charged."
    }
}

/** Draws the receipt and opens the share sheet. The image is overwritten by the next receipt. */
private fun shareReceipt(context: Context, flow: UssdFlow, outcome: Outcome.Success, time: Date) {
    val file = writeReceipt(context, flow, outcome, time)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share receipt"))
}

/**
 * Draws the receipt to `cache/receipts/receipt.png`. Always in the light palette, whatever the phone's
 * theme: it is read in other people's chats and printed, where a white slip is what a receipt looks like.
 */
internal fun writeReceipt(context: Context, flow: UssdFlow, outcome: Outcome.Success, time: Date): File {
    val bmp = Receipt(context).draw(flow, outcome, time)
    val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
    return File(dir, "receipt.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
}

private class Receipt(context: Context) {
    // The light palette from Theme.kt.
    private val bg = 0xFFF2F2F5.toInt()
    private val card = 0xFFFFFFFF.toInt()
    private val rail = 0xFFF2F2F5.toInt()
    private val ink = 0xFF0B0B0D.toInt()
    private val soft = 0xFF66666E.toInt()
    private val line = 0xFFD3D3DA.toInt()
    private val accent = 0xFF2667F0.toInt()

    private val font = ResourcesCompat.getFont(context, R.font.geist) ?: Typeface.DEFAULT

    // The light-theme mark, even when the phone is in dark mode (drawable-night has a white stroke).
    private val mark = run {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            android.content.res.Configuration.UI_MODE_NIGHT_NO
        androidx.core.content.ContextCompat.getDrawable(context.createConfigurationContext(config), R.drawable.logo_mark)
    }

    private fun text(size: Float, weight: Int, color: Int, tracking: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        fontVariationSettings = "'wght' $weight" // Geist is a variable font: real weights, not fake bold
        textSize = size
        letterSpacing = tracking
        this.color = color
    }

    /** Baseline that centres one line of [p] on [centerY]. */
    private fun baseline(p: Paint, centerY: Float) = centerY - (p.descent() + p.ascent()) / 2

    fun draw(flow: UssdFlow, outcome: Outcome.Success, time: Date): Bitmap {
        val w = 1080
        val m = 48f // card margin
        val left = m + 64f
        val right = w - m - 64f
        val width = right - left
        val bmp = Bitmap.createBitmap(w, 2000, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // Card first, sized at the end: draw everything into a recording pass, then the card behind it.
        val content = android.graphics.Picture()
        val pc = content.beginRecording(w, 2000)
        var y = m + 72f

        // Lockup: mark and wordmark, with the kind on the right.
        val markH = 60f
        val markW = markH * 42f / 36f
        mark?.setBounds(left.toInt(), y.toInt(), (left + markW).toInt(), (y + markH).toInt())
        mark?.draw(pc)
        val word = text(52f, 700, ink, -0.03f)
        pc.drawText("chalaiden", left + markW + 22f, baseline(word, y + markH / 2), word)
        val kind = text(34f, 500, soft)
        val kindLabel = when (flow.kind) {
            FlowKind.Payment -> "Payment"
            FlowKind.CashOut -> "Cash out"
            else -> "Send money"
        }
        pc.drawText(kindLabel, right - kind.measureText(kindLabel), baseline(kind, y + markH / 2), kind)
        y += markH + 120f

        // Status: the app's success badge, then the verb.
        val d = 64f
        fill.color = accent
        pc.drawCircle(left + d / 2, y + d / 2, d / 2, fill)
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = card
        }
        pc.drawPath(android.graphics.Path().apply {
            moveTo(left + d * 0.29f, y + d * 0.52f); lineTo(left + d * 0.44f, y + d * 0.66f); lineTo(left + d * 0.72f, y + d * 0.37f)
        }, tick)
        val verb = text(44f, 600, ink, -0.02f)
        val done = when (flow.kind) {
            FlowKind.Payment -> "Paid"
            FlowKind.CashOut -> "Cashed out"
            else -> "Sent"
        }
        pc.drawText(done, left + d + 24f, baseline(verb, y + d / 2), verb)
        y += d + 40f

        // Amount: the number large, "TK" quiet after it; shrinks so 999,999 still fits.
        val amount = "%,d".format(flow.amount?.toLongOrNull() ?: 0)
        var size = 176f
        lateinit var big: Paint
        lateinit var tk: Paint
        while (true) {
            big = text(size, 600, ink, -0.035f).apply { fontFeatureSettings = "tnum" }
            tk = text(size * 0.36f, 500, soft)
            if (big.measureText(amount) + 20f + tk.measureText("TK") <= width || size < 90f) break
            size -= 8f
        }
        val cap = android.graphics.Rect().also { big.getTextBounds("0", 0, 1, it) }.height()
        val amountBase = y + cap
        pc.drawText(amount, left, amountBase, big)
        pc.drawText("TK", left + big.measureText(amount) + 20f, amountBase, tk)
        y = amountBase + 64f

        // Recipient, on the same rail the app shows, grouped the way the card printed it.
        flow.recipient?.let { number ->
            val label = text(32f, 400, soft)
            pc.drawText("To", left, y + 32f, label)
            y += 56f
            val railH = 112f
            fill.color = rail
            pc.drawRoundRect(left, y, right, y + railH, railH / 2, railH / 2, fill)
            val digits = text(52f, 500, ink, 0.06f)
            val parts = split(number, flow.groups ?: PhoneGroups)
            val gap = 46f
            val total = parts.sumOf { digits.measureText(it).toDouble() }.toFloat() + gap * (parts.size - 1)
            var x = left + (width - total) / 2
            val base = baseline(digits, y + railH / 2)
            fill.color = line
            parts.forEachIndexed { i, part ->
                pc.drawText(part, x, base, digits)
                x += digits.measureText(part)
                if (i < parts.lastIndex) {
                    pc.drawRoundRect(x + gap / 2 - 1.5f, y + railH * 0.32f, x + gap / 2 + 1.5f, y + railH * 0.68f, 1.5f, 1.5f, fill)
                    x += gap
                }
            }
            y += railH
        }
        y += 72f
        val tear = y
        y += 72f

        // Details.
        val rows = listOfNotNull(
            outcome.trxId?.let { "TrxID" to it },
            flow.reference?.takeUnless(Flows::isDefaultReference)?.let { "Reference" to it },
            "Time" to SimpleDateFormat("h:mm a", Locale.ENGLISH).format(time),
            "Date" to SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(time),
        )
        val key = text(36f, 400, soft)
        rows.forEachIndexed { i, (k, v) ->
            val value = text(38f, 500, ink, if (k == "TrxID") 0.04f else 0f)
            // A long reference shrinks rather than running into its label.
            val room = width - key.measureText(k) - 40f
            while (value.measureText(v) > room && value.textSize > 24f) value.textSize -= 2f
            val base = y + 40f
            pc.drawText(k, left, base, key)
            pc.drawText(v, right - value.measureText(v), base, value)
            y += if (i < rows.lastIndex) 76f else 40f
        }
        y += 72f
        val cardBottom = y
        content.endRecording()

        // The card, with a perforated tear line and notches cut into both edges.
        fill.color = card
        c.drawRoundRect(m, m, w - m, cardBottom, 44f, 44f, fill)
        c.drawPicture(content)
        fill.color = bg
        c.drawCircle(m, tear, 22f, fill)
        c.drawCircle(w - m, tear, 22f, fill)
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = line; strokeWidth = 3f; style = Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 12f), 0f)
        }
        c.drawLine(m + 40f, tear, w - m - 40f, tear, dash)

        // Large enough to survive a chat app's recompression.
        val foot = text(36f, 400, soft)
        val note = "$done through bKash *247# with Chalaiden"
        c.drawText(note, (w - foot.measureText(note)) / 2, cardBottom + 84f, foot)

        val height = (cardBottom + 84f + 64f).toInt()
        return Bitmap.createBitmap(bmp, 0, 0, w, height)
    }

    /** The number cut into [groups] (e.g. 3-4-4); as one block if the groups don't add up to it. */
    private fun split(number: String, groups: List<Int>): List<String> {
        if (groups.sum() != number.length) return listOf(number)
        var at = 0
        return groups.map { n -> number.substring(at, at + n).also { at += n } }
    }
}

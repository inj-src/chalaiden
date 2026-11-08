package com.chalaiden.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chalaiden.app.keyboard.ChalaidenKeyboard
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.components.TextAction
import com.chalaiden.app.ui.components.TopBar
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.Method
import com.chalaiden.app.ussd.ShizukuAccess
import kotlinx.coroutines.launch

/** A button on a guide page. */
private class GuideAction(val label: String, val onClick: () -> Unit)

/**
 * One page of a set-up guide. [done]: true once the phone shows this step is finished, false while it
 * isn't, null for a page that only explains.
 */
private class GuidePage(
    val title: String,
    val body: String,
    val art: @Composable () -> Unit,
    val done: Boolean? = null,
    val tip: String? = null,
    val action: GuideAction? = null,
    val secondary: GuideAction? = null,
)

/**
 * Step-by-step set-up for one way of answering bKash, each step acted out by a looping picture
 * ([GuideArt.kt]). It follows the phone as it changes: Shizuku installed, running, allowed; root granted;
 * the overlay allowed. Finished steps get a tick, and coming back from a finished step moves to the next.
 */
@Composable
fun MethodGuideScreen(
    method: Method,
    shizuku: ShizukuAccess.Status,
    shizukuOk: Boolean,
    rootOk: Boolean,
    inUse: Method?,
    onRecheck: () -> Unit,
    onUse: (Method) -> Unit,
    onBack: () -> Unit,
) {
    val t = tokens
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // bumps on resume, after a trip to Settings or Shizuku
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val canFloat = remember(tick) { Settings.canDrawOverlays(context) }
    val keyboardOn = remember(tick) { ChalaidenKeyboard.isEnabled(context) }
    val developer = remember(tick) {
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }
            .getOrDefault(false)
    }

    // Fixed when the guide opens, so the steps don't change under the user once Shizuku is installed.
    val install = remember { shizuku.takeIf { it == ShizukuAccess.Status.NotInstalled || it == ShizukuAccess.Status.Outdated } }
    val pages = when (method) {
        Method.Shizuku -> shizukuPages(context, install, shizuku, shizukuOk, developer, onRecheck)
        Method.Root -> rootPages(rootOk, onRecheck)
        Method.Guided -> guidedPages(context, canFloat, keyboardOn)
    }
    // Where to be: the first step that isn't finished yet.
    val next = pages.indexOfFirst { it.done != true }.let { if (it < 0) pages.lastIndex else it }
    val pager = rememberPagerState(initialPage = next) { pages.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(next) { if (next > pager.currentPage) pager.animateScrollToPage(next) }

    val works = when (method) {
        Method.Root -> rootOk
        Method.Shizuku -> shizukuOk
        Method.Guided -> true
    }
    val name = when (method) {
        Method.Root -> "root"
        Method.Shizuku -> "Shizuku"
        Method.Guided -> "guided mode"
    }

    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        TopBar(
            when (method) {
                Method.Shizuku -> "Set up Shizuku"
                Method.Root -> "Set up root"
                Method.Guided -> "Guided mode"
            },
            onBack,
        )
        HorizontalPager(pager, Modifier.weight(1f), verticalAlignment = Alignment.Top) { i ->
            val page = pages[i]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                page.art()
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pages.size > 1) Text("Step ${i + 1} of ${pages.size}", style = Type.label, color = t.inkSoft)
                    AnimatedVisibility(page.done == true, enter = fadeIn() + scaleIn(initialScale = 0.6f)) {
                        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(t.accent), contentAlignment = Alignment.Center) {
                                Icon(AppIcons.Check, null, tint = t.onAccent, modifier = Modifier.size(12.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Done", style = Type.label, color = t.accent)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(page.title, style = Type.title.copy(fontSize = 24.sp, lineHeight = 30.sp), color = t.ink)
                Spacer(Modifier.height(8.dp))
                Text(page.body, style = Type.body, color = t.inkSoft)
                page.tip?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        it, style = Type.label, color = t.inkSoft,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(t.surface).padding(14.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        val i = pager.currentPage
        val page = pages[i]
        val last = i == pages.lastIndex
        val toNext = { scope.launch { pager.animateScrollToPage(i + 1) }; Unit }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (pages.size > 1) {
                PageDots(pages.size, i, pages.map { it.done == true })
                Spacer(Modifier.height(14.dp))
            }
            when {
                page.action != null && page.done != true -> PrimaryButton(page.action.label, onClick = page.action.onClick)
                last && works && inUse != method -> PrimaryButton("Use $name") { onUse(method) }
                last -> PrimaryButton("Done", onClick = onBack)
                else -> PrimaryButton("Next", onClick = toNext)
            }
            // Room for one quieter action, kept even when empty so the buttons don't jump between pages.
            Row(Modifier.height(52.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                page.secondary?.takeIf { page.done != true }?.let { TextAction(it.label, color = t.accent, onClick = it.onClick) }
                if (page.action != null && page.done != true && !last) TextAction("Next", onClick = toNext)
            }
        }
    }
}

/** Dots under the pages: the current one is a wider bar, finished ones are blue. */
@Composable
private fun PageDots(count: Int, current: Int, done: List<Boolean>) {
    val t = tokens
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { k ->
            val width by animateDpAsState(if (k == current) 22.dp else 7.dp, label = "dot")
            val color by animateColorAsState(
                when {
                    k == current -> t.ink
                    done[k] -> t.accent
                    else -> t.raised
                },
                label = "dot-color",
            )
            Box(Modifier.width(width).height(7.dp).clip(RoundedCornerShape(50)).background(color))
        }
    }
}

private fun shizukuPages(
    context: Context,
    /** Not installed or too old when the guide opened: the guide starts with getting it. */
    install: ShizukuAccess.Status?,
    status: ShizukuAccess.Status,
    works: Boolean,
    developer: Boolean,
    onRecheck: () -> Unit,
): List<GuidePage> {
    val installed = status != ShizukuAccess.Status.NotInstalled && status != ShizukuAccess.Status.Outdated
    val running = status == ShizukuAccess.Status.NeedsPermission || status == ShizukuAccess.Status.Denied ||
        status == ShizukuAccess.Status.Ready
    val openShizuku = GuideAction("Open Shizuku") { ShizukuAccess.openApp(context) }
    return buildList {
        // Installing is only shown to people who didn't have it (or had one too old).
        if (install != null) {
            val update = install == ShizukuAccess.Status.Outdated
            add(GuidePage(
                title = if (update) "Update Shizuku" else "Install Shizuku",
                body = if (update) {
                    "This Shizuku is too old for Chalaiden. Update it on Google Play."
                } else {
                    "Shizuku is free on Google Play. It's an open-source app that lets apps like Chalaiden use " +
                        "Android's own developer tools, without root."
                },
                art = { InstallArt(update) },
                done = installed,
                action = GuideAction(if (update) "Update on Google Play" else "Get it on Google Play") { ShizukuAccess.openStore(context) },
            ))
        }
        add(GuidePage(
            title = "Turn on Developer options",
            body = "Open Settings › About phone and tap Build number seven times, until it says you're a developer. " +
                "On OnePlus, it's in About device › Version.",
            art = { DeveloperArt() },
            done = developer || running,
            action = GuideAction("Open About phone") { open(context, Settings.ACTION_DEVICE_INFO_SETTINGS) },
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(GuidePage(
                title = "Pair Shizuku",
                body = "In Shizuku, tap Pairing. Then open Developer options › Wireless debugging, turn it on and tap " +
                    "Pair device with pairing code. Type the code into Shizuku's notification. You only pair once.",
                art = { PairArt() },
                done = running,
                tip = "Wireless debugging needs Wi-Fi switched on and connected; no internet is needed. " +
                    "On a rooted phone you can skip pairing: Shizuku starts with root.",
                action = openShizuku,
                secondary = GuideAction("Open Developer options") { open(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
            ))
        } else {
            add(GuidePage(
                title = "Connect to a computer",
                body = "On Android 10 and older, Shizuku is started from a computer. Turn on USB debugging in Developer " +
                    "options, connect the phone, and run the command Shizuku shows under \"Start by connecting to a computer\".",
                art = { PairArt() },
                done = running,
                action = openShizuku,
            ))
        }
        add(GuidePage(
            title = "Start Shizuku",
            body = "In Shizuku, tap Start. It says \"Shizuku is running\" when it's ready. " +
                "After the phone restarts, open Shizuku and tap Start again.",
            art = { StartArt() },
            done = running,
            tip = "On a rooted phone, use the Start button under \"Start (for rooted devices)\" instead.",
            action = openShizuku,
        ))
        add(GuidePage(
            title = "Allow Chalaiden",
            body = "Chalaiden asks Shizuku for access. Choose \"Allow all the time\". " +
                "Then Chalaiden answers bKash for you, behind its own screen.",
            art = { AllowShizukuArt() },
            done = works,
            tip = if (status == ShizukuAccess.Status.Denied) {
                "Chalaiden was refused before. In Shizuku, open the list of apps and switch Chalaiden on."
            } else null,
            action = when (status) {
                ShizukuAccess.Status.NeedsPermission -> GuideAction("Allow in Shizuku") { ShizukuAccess.request { onRecheck() } }
                ShizukuAccess.Status.Denied -> openShizuku
                ShizukuAccess.Status.Ready -> GuideAction("Try again", onRecheck)
                else -> null
            },
        ))
    }
}

/** Root users know their way around: which managers work, then the permission. */
private fun rootPages(rootOk: Boolean, onRecheck: () -> Unit) = listOf(
    GuidePage(
        title = if (rootOk) "Root is allowed" else "Allow root",
        body = "Works with Magisk, KernelSU and APatch. Tap Grant root and allow Chalaiden when your root manager asks.",
        art = { RootArt() },
        done = rootOk,
        tip = "Said no before? Switch Chalaiden on in your root manager's Superuser list, then tap Grant root again.",
        action = GuideAction("Grant root", onRecheck),
    ),
)

private fun guidedPages(context: Context, canFloat: Boolean, keyboardOn: Boolean) = listOf(
    GuidePage(
        title = "Turn on the Chalaiden keyboard",
        body = "It types each reply into bKash's box for you, and gives you a PIN pad when bKash asks for your PIN. " +
            "Switch on Chalaiden in Settings → Keyboards. Before each payment, pick it when Chalaiden asks.",
        art = { AutoFillArt() },
        done = keyboardOn,
        action = GuideAction("Open keyboard settings") { ChalaidenKeyboard.openSettings(context) },
    ),
    GuidePage(
        title = "Allow the floating card",
        body = "A small card above bKash's menu shows which step you're on. For that, Chalaiden needs to " +
            "display over other apps.",
        art = { OverlayArt() },
        done = canFloat,
        action = GuideAction("Allow display over other apps") {
            open(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        },
    ),
    GuidePage(
        title = "Your PIN, typed by you",
        body = "Check each reply and tap Send. At the PIN, the keyboard turns into a PIN pad: you type it, and " +
            "each digit goes straight into bKash's box. Chalaiden never fills it in and keeps nothing.",
        art = { PinArt() },
    ),
)

private fun open(context: Context, action: String, data: Uri? = null) {
    runCatching { context.startActivity(Intent(action, data)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}

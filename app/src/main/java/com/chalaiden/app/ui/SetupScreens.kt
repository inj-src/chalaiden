package com.chalaiden.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chalaiden.app.R
import com.chalaiden.app.keyboard.ChalaidenKeyboard
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.components.TextAction
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import com.chalaiden.app.ussd.Dialer
import com.chalaiden.app.ussd.Method
import com.chalaiden.app.ussd.Prefs
import com.chalaiden.app.ussd.ShizukuAccess
import com.chalaiden.app.ussd.Sim
import kotlinx.coroutines.launch

private const val PHONE = 0
private const val SIM = 1
private const val WAY = 2
private const val STEPS = 3

/**
 * Setup as three pages, one thing each: phone access, the bKash SIM, and how bKash gets answered
 * (Shizuku, root or guided mode; any one is enough). It opens on the first unfinished step, or on the
 * last when everything is done, since that is the one people come back to change.
 */
@Composable
fun SetupScreen(
    prefs: Prefs,
    rootOk: Boolean,
    shizukuOk: Boolean,
    shizuku: ShizukuAccess.Status,
    /** The way bKash gets answered now; null while none works. */
    inUse: Method?,
    /** Switches to a way (and, for root or Shizuku, asks for it if needed). */
    onChoose: (Method) -> Unit,
    /** Opens the step-by-step guide for a way. */
    onGuide: (Method) -> Unit,
    onRecheck: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val t = tokens
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // bumps on resume so checks re-run
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var denied by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { !it }) denied = true
        tick++
    }

    val phoneOk = remember(tick) { Dialer.hasPermissions(context) }
    val sims = remember(tick) { Dialer.sims(context) }
    var simId by remember(tick) { mutableStateOf(prefs.simSubscriptionId?.takeIf { id -> sims.any { it.subscriptionId == id } }) }
    if (simId == null && sims.size == 1) { simId = sims.first().subscriptionId; prefs.simSubscriptionId = simId }
    val canFloat = remember(tick) { Settings.canDrawOverlays(context) }
    val keyboardOn = remember(tick) { ChalaidenKeyboard.isEnabled(context) }
    // Android stops showing its prompt after the second "Don't allow"; then only Settings can grant it.
    val blocked = !phoneOk && denied && (context as? Activity)?.let { a ->
        Dialer.permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(a, it) }
    } == true

    val pager = rememberPagerState(initialPage = when {
        !phoneOk -> PHONE
        simId == null -> SIM
        else -> WAY
    }) { STEPS }
    val scope = rememberCoroutineScope()
    val go = { page: Int -> scope.launch { pager.animateScrollToPage(page) }; Unit }
    // Allowed just now, from this page: straight on to the SIM.
    val hadPhone = remember { phoneOk }
    LaunchedEffect(phoneOk) { if (phoneOk && !hadPhone && pager.currentPage == PHONE) pager.animateScrollToPage(SIM) }
    BackHandler(enabled = pager.currentPage > 0) { go(pager.currentPage - 1) }

    var choice by rememberSaveable {
        mutableStateOf(inUse ?: prefs.method ?: if (rootOk) Method.Root else if (shizukuOk) Method.Shizuku else null)
    }
    LaunchedEffect(inUse) { if (choice == null && inUse != null) choice = inUse }

    // A step only counts as done once the ones before it are.
    val done = listOf(phoneOk, phoneOk && simId != null, phoneOk && simId != null && inUse != null)
    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        SetupHeader(pager.currentPage, done, onBack = { if (pager.currentPage > 0) go(pager.currentPage - 1) else onBack() })
        HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = false, verticalAlignment = Alignment.Top) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                when (page) {
                    PHONE -> PhoneStep(phoneOk)
                    SIM -> SimStep(phoneOk, sims, simId) { simId = it; prefs.simSubscriptionId = it }
                    else -> WayStep(
                        choice = choice,
                        onPick = { choice = it },
                        inUse = inUse,
                        rootOk = rootOk,
                        shizukuOk = shizukuOk,
                        shizuku = shizuku,
                        keyboardOn = keyboardOn,
                        canFloat = canFloat,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // The buttons for the page in front. The second line keeps its height when empty, so the main
        // button doesn't jump from page to page.
        var primary: Pair<String, () -> Unit>? = null
        var secondary: Pair<String, () -> Unit>? = null
        val finish = {
            when {
                !phoneOk -> go(PHONE)
                simId == null -> go(SIM)
                else -> onDone()
            }
        }
        when (pager.currentPage) {
            PHONE -> primary = when {
                phoneOk -> "Continue" to { go(SIM) }
                blocked -> "Open app settings" to {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                    Unit
                }
                else -> "Allow phone access" to { ask.launch(Dialer.permissions) }
            }
            SIM -> primary = "Continue" to { go(WAY) }
            else -> when (choice) {
                Method.Shizuku -> {
                    primary = when {
                        inUse == Method.Shizuku -> "Finish" to finish
                        shizukuOk -> "Use Shizuku" to { onChoose(Method.Shizuku) }
                        else -> shizukuAction(shizuku) to {
                            when (shizuku) {
                                ShizukuAccess.Status.NeedsPermission ->
                                    ShizukuAccess.request { granted -> if (granted) onChoose(Method.Shizuku) else onRecheck() }
                                ShizukuAccess.Status.Denied -> ShizukuAccess.openApp(context)
                                ShizukuAccess.Status.Ready -> onChoose(Method.Shizuku)
                                // Not installed, too old or not started: the guide walks through it.
                                else -> onGuide(Method.Shizuku)
                            }
                        }
                    }
                    secondary = "Show me how" to { onGuide(Method.Shizuku) }
                }
                Method.Root -> {
                    primary = when {
                        inUse == Method.Root -> "Finish" to finish
                        rootOk -> "Use root" to { onChoose(Method.Root) }
                        else -> "Grant root" to { onChoose(Method.Root) }
                    }
                    secondary = "Show me how" to { onGuide(Method.Root) }
                }
                Method.Guided -> {
                    // The keyboard types the replies and the card shows the step; either one is enough to go.
                    val finishAnyway = "Finish without it" to finish
                    when {
                        inUse != Method.Guided -> primary = "Use guided mode" to { onChoose(Method.Guided) }
                        !keyboardOn -> {
                            primary = "Turn on Chalaiden keyboard" to { ChalaidenKeyboard.openSettings(context) }
                            secondary = if (canFloat) finishAnyway else "Show me how" to { onGuide(Method.Guided) }
                        }
                        !canFloat -> {
                            primary = "Allow the floating card" to {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                                }
                                Unit
                            }
                            secondary = finishAnyway
                        }
                        else -> primary = "Finish" to finish
                    }
                    if (secondary == null) secondary = "Show me how" to { onGuide(Method.Guided) }
                }
                null -> Unit
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val p = primary
            if (p != null) {
                PrimaryButton(p.first, enabled = pager.currentPage != SIM || simId != null, onClick = p.second)
            } else {
                PrimaryButton("Pick one", enabled = false) {}
            }
            Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) {
                secondary?.let { (label, action) -> TextAction(label, color = t.accent, onClick = action) }
            }
        }
    }
}

/** Back, then one bar per step: white for the one in front, blue once a step is done. */
@Composable
private fun SetupHeader(page: Int, done: List<Boolean>, onBack: () -> Unit) {
    val t = tokens
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(t.raised).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Back, contentDescription = "Back", tint = t.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(18.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(STEPS) { k ->
                val color by animateColorAsState(
                    when {
                        k == page -> t.ink
                        done[k] -> t.accent
                        else -> t.raised
                    },
                    label = "step-bar",
                )
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(color))
            }
        }
        Spacer(Modifier.width(14.dp))
        Text("${page + 1} of $STEPS", style = Type.label, color = t.inkSoft)
        Spacer(Modifier.width(4.dp))
    }
}

/** Title and a line under it, at the top of each step. */
@Composable
private fun StepHeading(title: String, body: String, bodyColor: Color = tokens.inkSoft) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = Type.display, color = tokens.ink)
    Spacer(Modifier.height(10.dp))
    Text(body, style = Type.body, color = bodyColor)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun PhoneStep(phoneOk: Boolean) {
    val t = tokens
    StepHeading(
        "Let Chalaiden open bKash",
        "Chalaiden dials *247# for you and checks which SIMs your phone has. It never makes other calls.",
    )
    PhoneAccessArt()
    AnimatedVisibility(phoneOk, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Tick(18)
            Spacer(Modifier.width(8.dp))
            Text("Phone access is on", style = Type.label, color = t.ink)
        }
    }
}

@Composable
private fun SimStep(phoneOk: Boolean, sims: List<Sim>, simId: Int?, onPick: (Int) -> Unit) {
    val t = tokens
    when {
        !phoneOk -> StepHeading("Which SIM is bKash on?", "Allow phone access first, so Chalaiden can see your SIMs.")
        sims.isEmpty() -> StepHeading("Which SIM is bKash on?", "No SIM found. Insert your bKash SIM.", bodyColor = t.error)
        sims.size == 1 -> StepHeading("Your bKash SIM", "Your phone has one SIM, so Chalaiden dials *247# on it.")
        else -> StepHeading("Which SIM is bKash on?", "Chalaiden dials *247# on the SIM you pick. You can change it on the home screen.")
    }
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        sims.forEach { sim -> SimTile(sim, sim.subscriptionId == simId, Modifier.weight(1f)) { onPick(sim.subscriptionId) } }
    }
}

/** A SIM as a card: its slot and network, ticked when it's the bKash one. */
@Composable
private fun SimTile(sim: Sim, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = tokens
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.clip(shape).background(t.surface)
            .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(AppIcons.Sim, null, tint = if (selected) t.accent else t.inkSoft, modifier = Modifier.size(36.dp))
            Spacer(Modifier.weight(1f))
            Radio(selected)
        }
        Spacer(Modifier.height(36.dp))
        Text("SIM ${sim.slot + 1}", style = Type.label, color = t.inkSoft)
        Text(sim.carrier, style = Type.title, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WayStep(
    choice: Method?,
    onPick: (Method) -> Unit,
    inUse: Method?,
    rootOk: Boolean,
    shizukuOk: Boolean,
    shizuku: ShizukuAccess.Status,
    keyboardOn: Boolean,
    canFloat: Boolean,
) {
    StepHeading("How should Chalaiden answer bKash?", "Any one of these is enough. You can switch later.")
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WayRow(
            icon = { AppTile(R.drawable.shizuku_icon, 40) },
            title = "Shizuku",
            tag = "No root needed",
            badge = badge(Method.Shizuku, inUse, shizukuOk),
            detail = shizukuBody(shizuku, shizukuOk),
            selected = choice == Method.Shizuku,
        ) { onPick(Method.Shizuku) }
        WayRow(
            icon = { RootTrio() },
            title = "Root",
            tag = "Rooted phones",
            badge = badge(Method.Root, inUse, rootOk),
            detail = if (rootOk) "Root allows Chalaiden. It answers bKash for you, behind its own screen."
            else "Chalaiden answers bKash for you. Allow it in your root manager when it asks.",
            selected = choice == Method.Root,
        ) { onPick(Method.Root) }
        WayRow(
            icon = {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tokens.raised), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Dialpad, null, tint = tokens.ink, modifier = Modifier.size(22.dp))
                }
            },
            title = "Guided mode",
            tag = "Any phone",
            badge = badge(Method.Guided, inUse, works = false),
            detail = when {
                inUse == Method.Guided && !keyboardOn ->
                    "Turn on the Chalaiden keyboard, so each reply is typed into bKash's box for you."
                inUse == Method.Guided && !canFloat ->
                    "Allow the floating card, so you see each step above bKash's menu."
                else -> "Chalaiden's keyboard types each reply into bKash's menu; you check it and tap Send. " +
                    "Your PIN goes on your own keyboard."
            },
            selected = choice == Method.Guided,
        ) { onPick(Method.Guided) }
    }
}

private fun badge(method: Method, inUse: Method?, works: Boolean) = when {
    inUse == method -> "In use"
    works -> "Ready"
    else -> null
}

/** One way to answer bKash; picking it opens a line on what it does and what's left to do. */
@Composable
private fun WayRow(
    icon: @Composable () -> Unit,
    title: String,
    tag: String,
    badge: String?,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = tokens
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(t.surface)
            .border(2.dp, if (selected) t.accent else Color.Transparent, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.title, color = t.ink)
                Text(tag, style = Type.label, color = t.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            badge?.let {
                Spacer(Modifier.width(8.dp))
                val on = it == "In use"
                Text(
                    it, style = Type.label, color = if (on) t.onAccent else t.inkSoft,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) t.accent else t.raised)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Radio(selected)
        }
        AnimatedVisibility(selected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(detail, style = Type.label, color = t.inkSoft, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** The three root managers in a small arch on a tile, as on the root guide: KernelSU on top. */
@Composable
private fun RootTrio() {
    val t = tokens
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(t.raised)) {
        listOf(
            R.drawable.root_magisk to (5 to 19),
            R.drawable.root_kernelsu to (13 to 5),
            R.drawable.root_apatch to (21 to 19),
        ).forEach { (icon, at) ->
            Image(
                painterResource(icon), null,
                Modifier.offset(x = at.first.dp, y = at.second.dp).size(14.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun AppTile(icon: Int, size: Int) {
    Image(painterResource(icon), null, Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)))
}

/** A radio button: a ring, or a blue disc with a tick. */
@Composable
private fun Radio(selected: Boolean) {
    val t = tokens
    if (selected) {
        Tick(22)
    } else {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, t.line, CircleShape))
    }
}

@Composable
private fun Tick(size: Int) {
    val t = tokens
    Box(Modifier.size(size.dp).clip(CircleShape).background(t.accent), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Check, null, tint = t.onAccent, modifier = Modifier.size((size * 0.62f).dp))
    }
}

private fun shizukuBody(status: ShizukuAccess.Status, works: Boolean) = if (works) {
    "Shizuku is running and allows Chalaiden. It answers bKash for you, behind its own screen."
} else when (status) {
    ShizukuAccess.Status.NotInstalled -> "A free app on Google Play that gives Chalaiden the same access as root, so it answers bKash for you. " +
        "It starts over Wireless Debugging, no computer needed on Android 11 and later."
    ShizukuAccess.Status.NotRunning -> "Shizuku is installed but not running. Start it in Shizuku, then come back. " +
        "It has to be started again after each restart of the phone."
    ShizukuAccess.Status.Outdated -> "This Shizuku is too old. Update it to version 11 or later."
    ShizukuAccess.Status.NeedsPermission -> "Shizuku is running. Allow Chalaiden when Shizuku asks."
    ShizukuAccess.Status.Denied -> "Shizuku was told not to allow Chalaiden. Allow it in Shizuku's list of apps."
    ShizukuAccess.Status.Ready -> "Shizuku allows Chalaiden, but its shell didn't start. Try again."
}

private fun shizukuAction(status: ShizukuAccess.Status) = when (status) {
    ShizukuAccess.Status.NotInstalled -> "Install Shizuku"
    ShizukuAccess.Status.Outdated -> "Update Shizuku"
    ShizukuAccess.Status.NotRunning -> "Set up Shizuku"
    ShizukuAccess.Status.NeedsPermission -> "Allow in Shizuku"
    ShizukuAccess.Status.Denied -> "Open Shizuku"
    ShizukuAccess.Status.Ready -> "Try again"
}

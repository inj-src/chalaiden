package com.chalaiden.app.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chalaiden.app.ui.components.capScale
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chalaiden.app.onetap.OneTap
import com.chalaiden.app.onetap.Touch
import com.chalaiden.app.onetap.askFingerprint
import com.chalaiden.app.onetap.fragmentActivity
import com.chalaiden.app.ui.components.AppIcons
import com.chalaiden.app.ui.components.Keypad
import com.chalaiden.app.ui.components.PinDots
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.components.TextAction
import com.chalaiden.app.ui.components.TopBar
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens

private const val PIN_DIGITS = 5

/**
 * One Tap, from Home's ⋮ menu: save the bKash PIN locked to the fingerprint, change it, or turn it off.
 * [guided]: the phone uses Guided mode, where One Tap can't help (the user types into bKash's own box).
 * [change]: open on typing a new PIN, after bKash refused the saved one.
 */
@Composable
fun OneTapScreen(guided: Boolean, change: Boolean, onBack: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val saved by OneTap.saved.collectAsState()
    var readiness by remember { mutableStateOf(OneTap.readiness(context)) }
    // Typing the PIN to save. Plain remember, never saveable: the PIN must not reach the saved state.
    var entering by remember { mutableStateOf(change && OneTap.saved.value && readiness == OneTap.Readiness.Ready) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        readiness = OneTap.readiness(context) // back from Settings
        // All fingerprints removed: Android has destroyed the key, so the saved PIN can never open again.
        if (readiness == OneTap.Readiness.NoneEnrolled && OneTap.saved.value) OneTap.forget(context)
        if (readiness != OneTap.Readiness.Ready) entering = false // nothing to lock a new PIN with
    }
    BackHandler(enabled = entering) { entering = false }

    Column(Modifier.fillMaxSize().background(t.bg).systemBarsPadding()) {
        TopBar("One Tap", onBack = { if (entering) entering = false else onBack() })
        when {
            entering && readiness == OneTap.Readiness.Ready -> SavePin(onSaved = { entering = false })
            readiness == OneTap.Readiness.NoneEnrolled -> Blocked(
                "Add a fingerprint first",
                "One Tap uses your phone's fingerprint lock. Add a fingerprint in Settings, then come back here.",
                action = "Open Settings" to { openEnroll(context) },
            )
            // Still on when the sensor is out for now, so it can be turned off.
            saved -> OnState(onChange = { entering = true }, onTurnOff = { OneTap.forget(context) }, guided = guided)
            readiness == OneTap.Readiness.Unavailable -> Blocked(
                "One Tap can't work on this phone",
                "It needs a fingerprint sensor that Android trusts for payments. You can keep typing your PIN.",
                action = null,
            )
            else -> Intro(guided, onStart = { entering = true })
        }
    }
}

@Composable
private fun Intro(guided: Boolean, onStart: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    val sensor = remember { OneTap.sensor(context) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            SensorArt(sensor)
            Spacer(Modifier.height(28.dp))
            Text("Pay with your fingerprint", style = Type.display, color = t.ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "Save your bKash PIN once. After that, a touch sends it for you. You can still type it any time.",
                style = Type.body, color = t.inkSoft,
            )
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (guided) GuidedNote()
                Fact(AppIcons.Lock, "Your PIN is locked inside this phone and only opens with your fingerprint. It goes only to bKash.")
                Fact(AppIcons.Alert, "Anyone whose fingerprint is on this phone can pay with it.")
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            PrimaryButton("Set up One Tap", onClick = onStart)
        }
    }
}

@Composable
private fun OnState(onChange: () -> Unit, onTurnOff: () -> Unit, guided: Boolean) {
    val t = tokens
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(t.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Fingerprint, contentDescription = null, tint = t.accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("One Tap is on", style = Type.display, color = t.ink)
        Spacer(Modifier.height(10.dp))
        val suspect by OneTap.suspect.collectAsState()
        Text(
            if (suspect) "bKash refused this saved PIN last time, so Chalaiden asks you to type it until you change it."
            else "Paying, sending, cashing out and checking your balance ask for your fingerprint. " +
                "Tap Use PIN in that box to type it instead.",
            style = Type.body, color = if (suspect) t.error else t.inkSoft,
        )
        if (guided) {
            Spacer(Modifier.height(16.dp))
            GuidedNote()
        }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(t.surface)) {
            Choice("Change saved PIN", t.ink, chevron = true, onClick = onChange)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = t.line)
            Choice("Turn off and remove PIN", t.error, chevron = false, onClick = onTurnOff)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Adding a fingerprint to this phone removes the saved PIN, so only fingers that were here when you saved it can pay.",
            style = Type.label.copy(lineHeight = 20.sp), color = t.inkSoft,
        )
    }
}

/** The PIN pad, then the fingerprint that locks what was typed. */
@Composable
private fun SavePin(onSaved: () -> Unit) {
    val t = tokens
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }
    fun save() {
        val activity = context.fragmentActivity() ?: return
        val cipher = runCatching { OneTap.encrypter(context) }.getOrElse {
            problem = "This phone couldn't make a lock for the PIN."
            return
        }
        val entered = pin
        asking = true
        activity.askFingerprint("Save your PIN", "Touch the sensor to lock it to your fingerprint", "Cancel", cipher, confirm = true) { touch ->
            asking = false
            when (touch) {
                is Touch.Unlocked -> runCatching { OneTap.store(context, touch.cipher, entered) }
                    .onSuccess { pin = ""; onSaved() }
                    .onFailure { problem = "Couldn't save it. Try again." }
                Touch.Declined -> Unit
                is Touch.Failed -> problem = touch.message
            }
        }
    }
    // On a short window at a large font size the keys can't shrink enough: then it all scrolls instead.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 560.dp
    Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Enter your bKash PIN", style = Type.display.copy(fontSize = 26.sp.capScale(), lineHeight = 31.sp.capScale()), color = t.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        // Capped and scrolling, so a large font size can't squeeze the keypad.
        Column(Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState())) {
            Text(
                problem ?: "It's locked to your fingerprint. If bKash ever says it's wrong, Chalaiden asks you what to do with it.",
                style = Type.label.copy(lineHeight = 20.sp), color = if (problem != null) t.error else t.inkSoft,
                // Only a problem is read out as it appears, not the hint coming back on the next digit.
                modifier = if (problem != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
        }
        Spacer(Modifier.height(24.dp))
        PinDots(pin.length, PIN_DIGITS, Modifier.align(Alignment.CenterHorizontally))
        Keypad(
            onKey = { if (pin.length < PIN_DIGITS) pin += it; problem = null },
            onBackspace = { pin = pin.dropLast(1) },
            modifier = if (compact) Modifier.padding(top = 16.dp) else Modifier.weight(1f).padding(top = 16.dp),
            keyHeight = if (compact) 48.dp else null,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Save PIN", enabled = pin.length == PIN_DIGITS && !asking, modifier = Modifier.padding(bottom = 12.dp)) { save() }
    }
    }
}

@Composable
private fun Blocked(title: String, body: String, action: Pair<String, () -> Unit>?) {
    val t = tokens
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(t.surface), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Fingerprint, contentDescription = null, tint = t.inkSoft, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(title, style = Type.display, color = t.ink)
            Spacer(Modifier.height(10.dp))
            Text(body, style = Type.body, color = t.inkSoft)
        }
        action?.let { (label, onClick) ->
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) { PrimaryButton(label, onClick = onClick) }
        }
    }
}

@Composable
private fun GuidedNote() = Fact(
    AppIcons.Alert,
    "You're using Guided mode, where you type into bKash's own box. One Tap works once Chalaiden answers bKash for you.",
)

@Composable
private fun Fact(icon: ImageVector, text: String) {
    val t = tokens
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = t.inkSoft, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Type.body, color = t.ink)
    }
}

@Composable
private fun Choice(label: String, color: Color, chevron: Boolean, onClick: () -> Unit) {
    val t = tokens
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.body, color = color, modifier = Modifier.weight(1f))
        if (chevron) Icon(AppIcons.ChevronRight, contentDescription = null, tint = t.inkSoft, modifier = Modifier.size(18.dp))
    }
}

private fun openEnroll(context: Context) {
    val enroll = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BIOMETRIC_STRONG)
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
    runCatching { context.startActivity(enroll) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
}

// ---------------------------------------------------------------------------------------------------
// Where the sensor is

/**
 * The phone with its sensor marked, and no words: the picture says it. Only the sensor moves: a slow
 * glow, and on a round sensor a ring easing out from it. Where the phone doesn't say, all three places
 * are shown, smaller.
 */
@Composable
private fun SensorArt(sensor: OneTap.Sensor) {
    val known = sensor != OneTap.Sensor.Unknown
    val kinds = if (known) listOf(sensor) else listOf(OneTap.Sensor.InDisplay, OneTap.Sensor.Side, OneTap.Sensor.Back)
    val motion = rememberSensorMotion()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        kinds.forEach { kind ->
            val t = tokens
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(t.surface).padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PhoneSketch(kind, if (known) 150.dp else 104.dp, motion)
            }
        }
    }
}

private class SensorMotion(val glow: State<Float>, val ring: State<Float>)

/** Still when the phone has animations turned off. */
@Composable
private fun rememberSensorMotion(): SensorMotion {
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (still) return remember { SensorMotion(mutableStateOf(1f), mutableStateOf(1f)) }
    val loop = rememberInfiniteTransition(label = "sensor")
    val glow = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val ring = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearOutSlowInEasing)), label = "ring")
    return remember { SensorMotion(glow, ring) }
}

@Composable
private fun PhoneSketch(kind: OneTap.Sensor, height: Dp, motion: SensorMotion) {
    val t = tokens
    val width = height * 0.5f
    val corner = RoundedCornerShape(width * 0.2f)
    // Room on the right for the side button, only where there is one: the others stay centred.
    Box(Modifier.size(width + if (kind == OneTap.Sensor.Side) 6.dp else 0.dp, height)) {
        Box(Modifier.size(width, height).border(2.dp, t.line, corner))
        when (kind) {
            OneTap.Sensor.InDisplay -> SensorSpot(
                Modifier.offset(x = width * 0.32f, y = height - width * 0.36f - height * 0.1f).size(width * 0.36f), motion,
            )
            OneTap.Sensor.Side -> Box(
                Modifier.offset(x = width + 1.dp, y = height * 0.28f).size(4.dp, height * 0.15f).clip(RoundedCornerShape(2.dp))
                    .drawBehind { drawRect(lerp(t.line, t.accent, motion.glow.value)) }, // read while drawing: no recomposition per frame
            )
            else -> {
                // The back: a camera block in the corner, the sensor below it in the middle.
                Box(Modifier.offset(x = width * 0.13f, y = width * 0.13f).size(width * 0.3f).border(2.dp, t.line, RoundedCornerShape(width * 0.08f)))
                SensorSpot(Modifier.offset(x = width * 0.32f, y = height * 0.32f).size(width * 0.36f), motion)
            }
        }
    }
}

@Composable
private fun SensorSpot(modifier: Modifier, motion: SensorMotion) {
    val t = tokens
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer {
                    val r = motion.ring.value
                    scaleX = 1f + 0.6f * r; scaleY = 1f + 0.6f * r; alpha = 0.45f * (1f - r)
                }
                .border(1.5.dp, t.accent, CircleShape),
        )
        Icon(
            AppIcons.Fingerprint, contentDescription = null,
            tint = t.accent,
            modifier = Modifier.matchParentSize().padding(2.dp).graphicsLayer { alpha = 0.4f + 0.6f * motion.glow.value },
        )
    }
}

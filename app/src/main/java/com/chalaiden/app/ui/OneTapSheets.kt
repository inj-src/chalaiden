package com.chalaiden.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chalaiden.app.ui.components.FingerprintTrace
import com.chalaiden.app.ui.components.PrimaryButton
import com.chalaiden.app.ui.components.TextAction
import com.chalaiden.app.ui.theme.Type
import com.chalaiden.app.ui.theme.tokens
import kotlinx.coroutines.launch

/**
 * Over Home, once ever, on the second time Chalaiden is opened: One Tap, offered where it can work.
 * [onSetUp] opens its screen; [onLater] ("Not now", a swipe, back) leaves it to the ⋮ menu.
 */
@Composable
fun OneTapOfferSheet(onSetUp: () -> Unit, onLater: () -> Unit) = ChoiceSheet(
    art = { color -> FingerprintTrace(color, Modifier.height(56.dp)) },
    title = "Pay with your fingerprint",
    body = "Save your bKash PIN once, and a touch sends it after that. You can still type it any time.",
    primary = "Set up One Tap" to onSetUp,
    secondary = "Not now" to onLater,
    onDismiss = onLater,
)

/**
 * Over the result, when bKash refused the PIN that One Tap sent. The user decides: save the right
 * one, or remove it. Swiping it away keeps the saved PIN as it is.
 */
@Composable
fun WrongPinSheet(onChange: () -> Unit, onRemove: () -> Unit, onKeep: () -> Unit) = ChoiceSheet(
    art = null,
    title = "Your saved PIN didn't work",
    body = "bKash says the PIN is invalid. That was the PIN One Tap saved, so it will fail again until you change it.",
    primary = "Change saved PIN" to onChange,
    secondary = "Remove it" to onRemove,
    onDismiss = onKeep,
)

/**
 * A sheet in the update sheet's shape: a title, a line, one main action and a quiet one. With [art],
 * everything is centred under it in an accent circle; without, it reads left-aligned like the update sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    art: (@Composable (Color) -> Unit)?,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
    onDismiss: () -> Unit,
) {
    val t = tokens
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Slide away first, then act: the next screen doesn't cut the sheet off mid-motion.
    fun close(then: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion { then() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = t.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        val centred = art != null
        val align = if (centred) TextAlign.Center else TextAlign.Start
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 8.dp),
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            art?.let {
                Box(Modifier.size(104.dp).clip(CircleShape).background(t.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    it(t.accent)
                }
                Spacer(Modifier.height(20.dp))
            }
            Text(title, style = Type.display.copy(fontSize = 26.sp, lineHeight = 31.sp), color = t.ink, textAlign = align, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(body, style = Type.body, color = t.inkSoft, textAlign = align, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            PrimaryButton(primary.first) { close(primary.second) }
            TextAction(secondary.first, Modifier.padding(top = 4.dp).then(if (centred) Modifier else Modifier.align(Alignment.CenterHorizontally)), color = t.inkSoft) {
                close(secondary.second)
            }
        }
    }
}

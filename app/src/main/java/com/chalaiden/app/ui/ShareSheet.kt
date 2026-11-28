package com.chalaiden.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chalaiden.app.ui.components.AppIcons

/**
 * Before Share Chalaiden hands the app over. With [quickShare], the one step people miss: the QR code is a
 * button inside Quick Share. Without, Android's share sheet opens and the user picks how to send it.
 */
@Composable
fun ShareSheet(quickShare: Boolean, onShare: () -> Unit, onCancel: () -> Unit) = ChoiceSheet(
    art = { color -> Icon(AppIcons.Share, contentDescription = null, tint = color, modifier = Modifier.size(44.dp)) },
    title = "Share Chalaiden",
    body = if (quickShare) {
        "Send the app to another phone, no internet needed. In Quick Share, turn on Bluetooth if it asks, then tap \"Use QR code\" and scan it with the other phone's camera."
    } else {
        "Send the app to another phone. Choose Quick Share or Bluetooth to send it without internet."
    },
    primary = (if (quickShare) "Open Quick Share" else "Share") to onShare,
    secondary = "Cancel" to onCancel,
    onDismiss = onCancel,
)

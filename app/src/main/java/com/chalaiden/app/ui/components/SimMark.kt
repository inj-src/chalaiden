package com.chalaiden.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.chalaiden.app.R
import com.chalaiden.app.ussd.Sim

/**
 * The operator's own mark for a Bangladeshi SIM. Matched on MCC-MNC first, since the carrier
 * name a phone reports varies ("GP", "Grameenphone", "grameenphone 4G"), then on the name.
 */
@DrawableRes
fun simLogo(sim: Sim): Int? = when (sim.network) {
    "47001" -> R.drawable.sim_gp
    "47002" -> R.drawable.sim_robi
    "47003" -> R.drawable.sim_banglalink
    "47004" -> R.drawable.sim_teletalk
    "47007" -> R.drawable.sim_airtel
    else -> sim.carrier.lowercase().let { name ->
        when {
            "grameen" in name || Regex("""\bgp\b""").containsMatchIn(name) -> R.drawable.sim_gp
            "robi" in name -> R.drawable.sim_robi
            "banglalink" in name -> R.drawable.sim_banglalink
            "teletalk" in name -> R.drawable.sim_teletalk
            "airtel" in name -> R.drawable.sim_airtel
            else -> null
        }
    }
}

/** The operator mark in full colour, or the generic SIM glyph tinted [fallbackTint]. */
@Composable
fun SimMark(sim: Sim?, fallbackTint: Color, modifier: Modifier = Modifier) {
    val logo = sim?.let(::simLogo)
    if (logo != null) Image(painterResource(logo), contentDescription = null, modifier = modifier)
    else Icon(AppIcons.Sim, contentDescription = null, tint = fallbackTint, modifier = modifier)
}

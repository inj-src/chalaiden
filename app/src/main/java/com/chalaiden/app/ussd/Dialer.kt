package com.chalaiden.app.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** [network] is the SIM's MCC-MNC, e.g. "47001" for Grameenphone, or blank when unknown. */
data class Sim(val subscriptionId: Int, val slot: Int, val carrier: String, val network: String = "")

object Dialer {
    val permissions = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)

    fun hasPermissions(context: Context) = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun sims(context: Context): List<Sim> {
        if (!hasPermissions(context)) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java)
        return sm.activeSubscriptionInfoList.orEmpty().map {
            val network = if (Build.VERSION.SDK_INT >= 29) "${it.mccString.orEmpty()}${it.mncString.orEmpty()}"
            else @Suppress("DEPRECATION") "%03d%02d".format(it.mcc, it.mnc)
            Sim(it.subscriptionId, it.simSlotIndex, it.carrierName?.toString().orEmpty().ifBlank { "SIM ${it.simSlotIndex + 1}" }, network)
        }.sortedBy { it.slot }
    }

    /**
     * Dials `*247#` on the chosen SIM. Started as an activity from Chalaiden in the foreground:
     * OxygenOS only shows the USSD dialog for a foreground launch.
     */
    fun callIntent(context: Context, subscriptionId: Int?): Intent =
        Intent(Intent.ACTION_CALL, Uri.fromParts("tel", Flows.CODE, null)).apply {
            accountFor(context, context.getSystemService(TelecomManager::class.java), subscriptionId)
                ?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }

    @SuppressLint("MissingPermission")
    private fun accountFor(context: Context, telecom: TelecomManager, subscriptionId: Int?): PhoneAccountHandle? {
        if (subscriptionId == null) return null
        val accounts = telecom.callCapablePhoneAccounts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val tm = context.getSystemService(TelephonyManager::class.java)
            return accounts.firstOrNull { tm.getSubscriptionId(it) == subscriptionId }
        }
        // Older releases use the subscription id or ICCID as the account id.
        val iccId = context.getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfo(subscriptionId)?.iccId
        return accounts.firstOrNull { it.id == subscriptionId.toString() || (iccId != null && it.id.startsWith(iccId)) }
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("chalaiden", Context.MODE_PRIVATE)

    var simSubscriptionId: Int?
        get() = sp.getInt("sim", -1).takeIf { it >= 0 }
        set(value) = sp.edit().putInt("sim", value ?: -1).apply()

    /**
     * Guided mode: when a guide ended with bKash still holding its session (see [GuideSession.Lifecycle]);
     * 0 when it holds none, or once the next guide has asked to say No to it.
     */
    var bkashLeftOpenAt: Long
        get() = sp.getLong("bkash_left_open_at", 0L)
        set(value) = sp.edit().putLong("bkash_left_open_at", value).apply()

    /**
     * A payment has gone through once. A single flag, not a record of the payment; Chalaiden keeps no
     * history. It folded Home's "What to scan" lesson into a link; nothing reads it since the lesson left Home.
     */
    var scanLearned: Boolean
        get() = sp.getBoolean("scan_learned", false)
        set(value) = sp.edit().putBoolean("scan_learned", value).apply()

    /** Which design Home's brand wears (a position in `HomeDesign`): the one the user last swiped to. */
    var homeDesign: Int
        get() = sp.getInt("home_design", 0)
        set(value) = sp.edit().putInt("home_design", value).apply()

    /** The keyboard a running session switched off, until it is switched back on. */
    var imeToRestore: String?
        get() = sp.getString("ime_to_restore", null)
        set(value) { sp.edit().putString("ime_to_restore", value).commit() }

    /**
     * How the user chose to answer bKash, in Setup. Null until they choose: then root is used if it works,
     * else Shizuku. A chosen way is kept even when another one works, so each can be tried out.
     * Older installs kept only a "guided" flag; that reads as [Method.Guided].
     */
    var method: Method?
        get() = sp.getString("method", null)?.let { name -> Method.entries.firstOrNull { it.name == name } }
            ?: Method.Guided.takeIf { sp.getBoolean("guided", false) }
        set(value) = sp.edit().putString("method", value?.name).remove("guided").apply()
}

/** The three ways Chalaiden can answer bKash's menu. Any one is enough. */
enum class Method {
    /** Automatic, with root. */
    Root,

    /** Automatic, with Shizuku (no root needed). */
    Shizuku,

    /** The user types each reply; Chalaiden floats it above bKash's menu. Any phone. */
    Guided,
}

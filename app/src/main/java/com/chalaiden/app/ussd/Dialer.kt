package com.chalaiden.app.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

data class Sim(val subscriptionId: Int, val slot: Int, val carrier: String)

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
            Sim(it.subscriptionId, it.simSlotIndex, it.carrierName?.toString().orEmpty().ifBlank { "SIM ${it.simSlotIndex + 1}" })
        }.sortedBy { it.slot }
    }

    /** Opens `*247#` on the chosen SIM. The phone app's USSD dialog takes it from there. */
    @SuppressLint("MissingPermission")
    fun dial(context: Context, subscriptionId: Int?) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val extras = Bundle()
        accountFor(context, telecom, subscriptionId)?.let {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
        }
        telecom.placeCall(Uri.fromParts("tel", Flows.CODE, null), extras)
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

    var disclosureAccepted: Boolean
        get() = sp.getBoolean("a11y_disclosure", false)
        set(value) = sp.edit().putBoolean("a11y_disclosure", value).apply()
}

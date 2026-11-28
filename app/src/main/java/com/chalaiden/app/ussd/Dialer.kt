package com.chalaiden.app.ussd

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
        }.sortedBy { it.slot }.ifEmpty { simsBySlot(context, sm) }
    }

    /**
     * SIMs read slot by slot, through calls that need no permission. Some phones hand an app an empty
     * SIM list, and refuse the calls behind it, with phone access allowed in every list Android and the
     * maker keep: REDMI K20 Pro, MIUI 12.5 China ROM, Android 11, 2026-11-23. Both slots were ready.
     */
    private fun simsBySlot(context: Context, sm: SubscriptionManager): List<Sim> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val tm = context.getSystemService(TelephonyManager::class.java)
        @Suppress("DEPRECATION") val slots = tm.phoneCount
        return (0 until slots).mapNotNull { slot ->
            runCatching {
                if (tm.getSimState(slot) != TelephonyManager.SIM_STATE_READY) return@runCatching null
                val id = if (Build.VERSION.SDK_INT >= 34) SubscriptionManager.getSubscriptionId(slot)
                else @Suppress("DEPRECATION") sm.getSubscriptionIds(slot)?.firstOrNull()
                if (id == null || id < 0) return@runCatching null
                val sim = tm.createForSubscriptionId(id)
                Sim(id, slot, sim.simOperatorName.orEmpty().ifBlank { "SIM ${slot + 1}" }, sim.simOperator.orEmpty())
            }.getOrNull()
        }
    }

    /** Debug: every way Android can tell us about SIMs, one line each, so a phone that hides them shows where. */
    @SuppressLint("MissingPermission")
    fun probe(context: Context): String = buildString {
        fun line(name: String, f: () -> Any?) = appendLine("$name = ${runCatching(f).getOrElse { "threw ${it.javaClass.simpleName}: ${it.message}" }}")
        val sm = context.getSystemService(SubscriptionManager::class.java)
        val tm = context.getSystemService(TelephonyManager::class.java)
        val telecom = context.getSystemService(TelecomManager::class.java)
        line("permissions") { permissions.associateWith { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } }
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        line("checkOp read_phone_state") { ops.unsafeCheckOpNoThrow("android:read_phone_state", android.os.Process.myUid(), context.packageName) }
        line("noteOp read_phone_state") { ops.noteOpNoThrow("android:read_phone_state", android.os.Process.myUid(), context.packageName, null, "probe") }
        line("PermissionChecker") { androidx.core.content.PermissionChecker.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) }
        line("opPackageName") { context.opPackageName + " / " + context.attributionTag }
        line("activeSubscriptionInfoList") { sm.activeSubscriptionInfoList?.map { "id=${it.subscriptionId} slot=${it.simSlotIndex} ${it.carrierName}" } }
        line("activeSubscriptionInfoCount") { sm.activeSubscriptionInfoCount }
        line("activeSubscriptionInfoCountMax") { sm.activeSubscriptionInfoCountMax }
        line("slot0") { sm.getActiveSubscriptionInfoForSimSlotIndex(0)?.subscriptionId }
        line("slot1") { sm.getActiveSubscriptionInfoForSimSlotIndex(1)?.subscriptionId }
        line("defaultSubscriptionId") { SubscriptionManager.getDefaultSubscriptionId() }
        line("defaultVoiceSubscriptionId") { SubscriptionManager.getDefaultVoiceSubscriptionId() }
        line("phoneCount") { @Suppress("DEPRECATION") tm.phoneCount }
        line("simState0") { if (Build.VERSION.SDK_INT >= 26) tm.getSimState(0) else null }
        line("simState1") { if (Build.VERSION.SDK_INT >= 26) tm.getSimState(1) else null }
        line("simOperatorName") { tm.simOperatorName }
        line("callCapablePhoneAccounts") { telecom.callCapablePhoneAccounts.map { it.id.takeLast(4) } }
        telecom.callCapablePhoneAccounts.forEachIndexed { i, h ->
            line("account$i.subId") { if (Build.VERSION.SDK_INT >= 30) tm.getSubscriptionId(h) else null }
            line("account$i.slot") { if (Build.VERSION.SDK_INT >= 30) SubscriptionManager.getSlotIndex(tm.getSubscriptionId(h)) else null }
            line("account$i.operator") { if (Build.VERSION.SDK_INT >= 30) tm.createForSubscriptionId(tm.getSubscriptionId(h)).let { "${it.simOperatorName} ${it.simOperator}" } else null }
            line("account$i.label") { telecom.getPhoneAccount(h)?.label }
            line("account$i.extras") { telecom.getPhoneAccount(h)?.extras?.let { e -> e.keySet().associateWith { e.get(it)?.toString()?.take(24) } } }
            line("account$i.component") { h.componentName.className.substringAfterLast('.') }
        }
        for (slot in 0 until 2) line("subIdsForSlot$slot") { SubscriptionManager::class.java.getMethod("getSubscriptionIds", Int::class.javaPrimitiveType).invoke(sm, slot).let { (it as? IntArray)?.toList() } }
    }

    /**
     * Dials `*247#` on the chosen SIM. Started as an activity from Chalaiden in the foreground:
     * OxygenOS only shows the USSD dialog for a foreground launch.
     */
    fun callIntent(context: Context, subscriptionId: Int?): Intent =
        Intent(Intent.ACTION_CALL, Uri.fromParts("tel", Flows.CODE, null)).apply {
            accountFor(context, context.getSystemService(TelecomManager::class.java), subscriptionId)
                ?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            systemCaller(context, this)?.let { component = it }
        }

    /**
     * The phone's own call handler, always: `*247#` is for the SIM, never for another calling app. A
     * VoIP app (ZoiPer on a REDMI K20 Pro, 2026-11-23) answers ACTION_CALL too, and Android then asks
     * "Complete action using" behind Chalaiden's cover, so bKash's menu never opens. Null (Android
     * picks, as before) unless exactly one system app takes calls.
     */
    private fun systemCaller(context: Context, intent: Intent): ComponentName? = runCatching {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
            .filter { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .singleOrNull()?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun accountFor(context: Context, telecom: TelecomManager, subscriptionId: Int?): PhoneAccountHandle? {
        if (subscriptionId == null) return null
        val accounts = telecom.callCapablePhoneAccounts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val tm = context.getSystemService(TelephonyManager::class.java)
            return runCatching { accounts.firstOrNull { tm.getSubscriptionId(it) == subscriptionId } }
                .getOrElse { accountBySlot(telecom, accounts, subscriptionId) }
        }
        // Older releases use the subscription id or ICCID as the account id.
        val iccId = context.getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfo(subscriptionId)?.iccId
        return accounts.firstOrNull { it.id == subscriptionId.toString() || (iccId != null && it.id.startsWith(iccId)) }
    }

    /**
     * For phones that refuse to say which account is which SIM ([simsBySlot]): Android's own SIM
     * accounts carry their slot as [SORT_ORDER]. Only an exact, single match counts;
     * otherwise null, and the phone asks for the SIM itself rather than Chalaiden guessing.
     */
    /** `PhoneAccount.EXTRA_SORT_ORDER`, hidden from the SDK; telephony sets it to the SIM's slot. */
    private const val SORT_ORDER = "android.telecom.extra.SORT_ORDER"

    private fun accountBySlot(telecom: TelecomManager, accounts: List<PhoneAccountHandle>, subscriptionId: Int): PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < 29) return null
        val slot = SubscriptionManager.getSlotIndex(subscriptionId).takeIf { it >= 0 } ?: return null
        return accounts.filter {
            runCatching { telecom.getPhoneAccount(it)?.extras?.getString(SORT_ORDER) == slot.toString() }
                .getOrDefault(false)
        }.singleOrNull()
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

    /** How many times Chalaiden has been opened, counted up to 2: One Tap is offered on the second. */
    var opens: Int
        get() = sp.getInt("opens", 0)
        set(value) = sp.edit().putInt("opens", value.coerceAtMost(2)).apply()

    /** One Tap's offer has been shown. It is shown once, ever; after that it lives in Home's ⋮ menu. */
    var oneTapOffered: Boolean
        get() = sp.getBoolean("one_tap_offered", false)
        set(value) = sp.edit().putBoolean("one_tap_offered", value).apply()

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

/** The ways Chalaiden can answer bKash's menu. Any one is enough. */
enum class Method {
    /** Automatic, with root. */
    Root,

    /** Automatic, with Shizuku (no root needed). */
    Shizuku,

    /** The user types each reply; Chalaiden floats it above bKash's menu. Any phone. */
    Guided,

    /** Automatic, with Chalaiden's accessibility service (no root or Shizuku). Off until the user switches it on. */
    Accessibility,
}

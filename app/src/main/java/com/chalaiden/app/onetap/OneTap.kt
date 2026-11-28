package com.chalaiden.app.onetap

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One Tap: the bKash PIN saved on this phone, so a fingerprint can send it instead of typing it.
 *
 * The PIN is encrypted with an AES key in the Android Keystore that only opens right after a strong
 * biometric (a fingerprint, on most phones). Chalaiden never holds the key; it holds only the encrypted
 * PIN, which is useless without it. Adding a fingerprint to the phone destroys the key, so only fingers
 * that were enrolled when the PIN was saved can ever send it.
 *
 * Only for the ways where Chalaiden answers bKash itself (Automatic, Shizuku, root); in Guided mode the
 * user types into bKash's own box.
 */
object OneTap {
    enum class Readiness { Ready, NoneEnrolled, Unavailable }

    /** Where the fingerprint sensor is, so setup can show the right one. Unknown on most phones. */
    enum class Sensor { InDisplay, Side, Back, Unknown }

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** The fingerprint sheet is up; the run cover stays down meanwhile, or it would hide the sheet. */
    val prompting = MutableStateFlow(false)

    /** "Use fingerprint" on the PIN screen: the activity answers with the sheet. */
    val asks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A line for the PIN screen when the fingerprint didn't work ("Too many tries…"). */
    val pinNotice = MutableStateFlow<String?>(null)

    /** A line for the result screen after a saved PIN turned out wrong and was removed. */
    val resultNotice = MutableStateFlow<String?>(null)

    /** This run's PIN came from One Tap, so a "wrong PIN" from bKash means the saved one is wrong. */
    @Volatile var usedThisRun = false

    fun init(context: Context) {
        val alias = prefs(context).getString(ALIAS, null)
        _saved.value = alias != null && prefs(context).contains(PIN) && runCatching { keyStore().containsAlias(alias) }.getOrDefault(false)
    }

    fun readiness(context: Context): Readiness = when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> Readiness.Ready
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Readiness.NoneEnrolled
        else -> Readiness.Unavailable
    }

    /**
     * A cipher to lock a new PIN with, under a fresh key. The key in use is left alone until [store]:
     * cancelling "Change saved PIN" halfway keeps the old PIN working. Two names take turns.
     */
    fun encrypter(context: Context): Cipher {
        val ks = keyStore()
        val next = if (prefs(context).getString(ALIAS, null) == KEY_A) KEY_B else KEY_A
        if (ks.containsAlias(next)) ks.deleteEntry(next)
        pending = next
        val spec = KeyGenParameterSpec.Builder(next, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // Every use needs its own fingerprint, and only a strong biometric counts.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** After the fingerprint: [cipher] (from [encrypter]) is unlocked; lock [pin] with it and keep it. */
    fun store(context: Context, cipher: Cipher, pin: String) {
        val sealed = cipher.doFinal(pin.toByteArray())
        val alias = pending ?: error("No key was made for this PIN")
        val old = prefs(context).getString(ALIAS, null)
        prefs(context).edit()
            .putString(ALIAS, alias)
            .putString(PIN, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        pending = null
        if (old != null && old != alias) runCatching { keyStore().deleteEntry(old) }
        _saved.value = true
    }

    /** The key [encrypter] just made, until [store] adopts it. */
    private var pending: String? = null

    /**
     * A cipher to open the saved PIN with, for the fingerprint sheet. Null when there is none, or when
     * the phone's fingerprints changed since it was saved: then it's gone for good and is removed.
     */
    fun decrypter(context: Context): Cipher? {
        val iv = prefs(context).getString(IV, null) ?: return null
        val alias = prefs(context).getString(ALIAS, null) ?: return null
        return try {
            val key = keyStore().getKey(alias, null) as? SecretKey ?: return null.also { forget(context) }
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            forget(context)
            null
        }
    }

    /** After the fingerprint: the saved PIN, opened with [cipher] (from [decrypter]). */
    fun read(context: Context, cipher: Cipher): String? = runCatching {
        val sealed = Base64.decode(prefs(context).getString(PIN, null) ?: return null, Base64.NO_WRAP)
        String(cipher.doFinal(sealed))
    }.getOrNull()

    /** Removes the saved PIN and its key. */
    fun forget(context: Context) {
        prefs(context).edit().remove(PIN).remove(IV).remove(ALIAS).apply()
        listOf(KEY_A, KEY_B).forEach { runCatching { keyStore().deleteEntry(it) } }
        _saved.value = false
    }

    /**
     * bKash's answer says the PIN was wrong. Only then is a saved PIN removed: any other refusal
     * ("Insufficient balance") says nothing about the PIN.
     */
    fun isWrongPin(text: String): Boolean {
        // TODO(user): bKash's exact wording for a wrong PIN.
        return WRONG_PIN.containsMatchIn(text)
    }

    private val WRONG_PIN = Regex("""\b(wrong|invalid|incorrect)\s+pin\b|\bpin\b.{0,20}\b(wrong|invalid|incorrect|mismatch)""", RegexOption.IGNORE_CASE)

    /**
     * Best effort: Android has no public way to ask where the sensor is. Phones built on AOSP's config
     * list their in-display or side sensor in framework resources; OPPO, realme and OnePlus name an
     * optical one in a system feature. Their other fingerprint features can't be trusted (a OnePlus with
     * an in-display sensor also lists "back.touch"), so a back sensor is never claimed. Everything else
     * is Unknown, and setup shows each kind.
     */
    fun sensor(context: Context): Sensor {
        val sys = Resources.getSystem()
        fun listed(name: String) = runCatching {
            val id = sys.getIdentifier(name, "array", "android")
            id != 0 && sys.obtainTypedArray(id).let { a -> (a.length() > 0).also { a.recycle() } }
        }.getOrDefault(false)
        if (listed("config_udfps_sensor_props")) return Sensor.InDisplay
        if (listed("config_sfps_sensor_props")) return Sensor.Side
        val features = context.packageManager.systemAvailableFeatures.mapNotNull { it.name?.lowercase() }
        val words = features.filter { "fingerprint" in it }.flatMap { it.split('.', '_') }.toSet()
        if (words.any { it in setOf("optical", "ultrasonic", "udfps", "fod", "underscreen", "indisplay") }) return Sensor.InDisplay
        return Sensor.Unknown
    }

    private fun keyStore() = KeyStore.getInstance(STORE).apply { load(null) }
    private fun prefs(context: Context) = context.getSharedPreferences("chalaiden_onetap", Context.MODE_PRIVATE)

    private const val STORE = "AndroidKeyStore"
    private const val KEY_A = "one_tap_pin_a"
    private const val KEY_B = "one_tap_pin_b"
    private const val ALIAS = "alias"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PIN = "pin"
    private const val IV = "iv"
}

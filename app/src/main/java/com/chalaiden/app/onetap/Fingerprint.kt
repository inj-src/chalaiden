package com.chalaiden.app.onetap

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/** How the phone's fingerprint sheet ended. */
sealed interface Touch {
    /** The finger matched; [cipher] is now unlocked for one use. */
    data class Unlocked(val cipher: Cipher) : Touch

    /** "Use PIN", "Cancel", back, or tapping outside: the user chose not to. Nothing to say. */
    data object Declined : Touch

    /** It couldn't be used (too many tries, sensor busy); [message] is Android's own, fit to show. */
    data class Failed(val message: String) : Touch
}

/**
 * Shows the phone's own fingerprint sheet: Android draws it, in the phone's style, with the in-display
 * sensor marked where it is. [cipher] is what the fingerprint unlocks; the result arrives on the main
 * thread. [confirm]: a passive biometric (a face) still needs a tap on Confirm, for anything that moves
 * money.
 */
fun FragmentActivity.askFingerprint(
    title: String,
    subtitle: String?,
    cancel: String,
    cipher: Cipher,
    confirm: Boolean,
    onResult: (Touch) -> Unit,
) {
    val prompt = BiometricPrompt(
        this,
        ContextCompat.getMainExecutor(this),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val unlocked = result.cryptoObject?.cipher
                onResult(if (unlocked != null) Touch.Unlocked(unlocked) else Touch.Failed("Your fingerprint couldn't open the saved PIN."))
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                onResult(
                    when (code) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED -> Touch.Declined
                        else -> Touch.Failed(message.toString())
                    },
                )
            }
            // A finger that doesn't match is handled inside the sheet ("Try again"); nothing to do here.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { subtitle?.let(::setSubtitle) }
        .setNegativeButtonText(cancel)
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .setConfirmationRequired(confirm)
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

/** The activity behind a Compose [Context], for the fingerprint sheet; null outside one. */
fun Context.fragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c != null) {
        if (c is FragmentActivity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

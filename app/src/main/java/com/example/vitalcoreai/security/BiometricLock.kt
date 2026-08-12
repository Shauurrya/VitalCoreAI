package com.example.vitalcoreai.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.vitalcoreai.R
import com.example.vitalcoreai.data.UserPrefs

/**
 * T-17 — optional biometric gate on app open.
 *
 * ## What this does and does not protect
 *
 * The app's data never leaves the device, so this is not about egress. It closes a
 * different, more ordinary gap: an unlocked phone handed to someone else. That is the whole
 * claim, and the settings copy says exactly that rather than implying encryption.
 *
 * Nothing biometric is read, stored or compared here. `BiometricPrompt` delegates to the
 * platform, which answers yes or no; the app holds no template and no key material.
 *
 * ## Why DEVICE_CREDENTIAL is allowed
 *
 * Falling back to the PIN, pattern or password matters because a user with no enrolled
 * fingerprint would otherwise be offered a lock that can never be satisfied — and, worse,
 * one that could leave them locked out of their own history after a sensor failure.
 */
object BiometricLock {

    /** BIOMETRIC_WEAK is sufficient: this gates a view, not a key. */
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    enum class Availability {
        /** A biometric or device credential is enrolled and usable. */
        AVAILABLE,

        /** Hardware exists but nothing is enrolled — offer to send the user to Settings. */
        NOT_ENROLLED,

        /** No usable hardware, or it is permanently unavailable. */
        UNSUPPORTED
    }

    fun availability(context: Context): Availability =
        when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NOT_ENROLLED
            else -> Availability.UNSUPPORTED
        }

    /** True when the user has switched the lock on AND the device can actually satisfy it. */
    fun isEnabledAndUsable(context: Context): Boolean =
        UserPrefs.biometricLockEnabled(context) && availability(context) == Availability.AVAILABLE

    /**
     * Show the prompt.
     *
     * @param onFailure called for a *terminal* error or a user cancellation — not for a
     *        single rejected fingerprint, which the platform prompt handles itself by
     *        letting the user try again. Treating every failed touch as a lockout would
     *        make the feature unusable with damp hands.
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (availability(activity) != Availability.AVAILABLE) {
            onFailure(activity.getString(R.string.biometric_unavailable))
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_title))
            .setSubtitle(activity.getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            // No negative button: setAllowedAuthenticators with DEVICE_CREDENTIAL supplies
            // its own, and setting both throws at build time.
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure(errString.toString())
                }
            }
        )
        prompt.authenticate(promptInfo)
    }
}

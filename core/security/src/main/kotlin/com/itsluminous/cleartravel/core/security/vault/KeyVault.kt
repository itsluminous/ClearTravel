package com.itsluminous.cleartravel.core.security.vault

import kotlinx.coroutines.flow.StateFlow
import javax.crypto.Cipher
import javax.crypto.SecretKey

/** Where the vault stands; drives the app-lock gate (ADR-031). */
sealed interface VaultState {
    /** No password yet — the blocking first-run setup must run. */
    data object NotSetUp : VaultState

    /** A password exists but the DEK is not in memory (cold start). */
    data class Locked(
        val biometricEnabled: Boolean,
    ) : VaultState

    /** DEK cached for the process lifetime; storage can be read and written. */
    data class Unlocked(
        val biometricEnabled: Boolean,
    ) : VaultState
}

/** Thrown when key material is requested while the vault is [VaultState.Locked]/[VaultState.NotSetUp]. */
class VaultLockedException : IllegalStateException("ClearTravel vault is locked")

/**
 * The password-derived portable key together with the KDF inputs that produced it,
 * so a writer can record salt + iterations in an envelope header and a reader can
 * tell whether a cached key matches a given header.
 */
class PortableKey(
    val salt: ByteArray,
    val iterations: Int,
    val key: SecretKey,
) {
    fun matches(
        salt: ByteArray,
        iterations: Int,
    ): Boolean = this.iterations == iterations && this.salt.contentEquals(salt)
}

/**
 * Holder of the master Data Encryption Key (ADR-031). One random 256-bit DEK per
 * install, wrapped by the password (always) and by a biometric Keystore key
 * (optional). Once unlocked, the DEK stays in process memory until the process dies
 * — background workers rely on this cache; nothing ever writes it unwrapped.
 *
 * Password change re-wraps the DEK only; no data is re-encrypted. Forgetting the
 * password is unrecoverable by design.
 */
interface KeyVault {
    val state: StateFlow<VaultState>

    val isUnlocked: Boolean get() = state.value is VaultState.Unlocked

    /** First-run: generates the DEK, wraps it under [password], unlocks. */
    suspend fun setUp(password: CharArray)

    /** True (and unlocked) when [password] unwraps the DEK. */
    suspend fun unlockWithPassword(password: CharArray): Boolean

    /** Re-wraps the DEK under [newPassword] after verifying [currentPassword]. */
    suspend fun changePassword(
        currentPassword: CharArray,
        newPassword: CharArray,
    ): Boolean

    /** Sub-key for the SQLCipher database (raw 32 bytes). Throws [VaultLockedException]. */
    fun databaseKey(): ByteArray

    /** Sub-key for on-device file encryption. Throws [VaultLockedException]. */
    fun fileKey(): SecretKey

    /** This vault's own password-derived portable key. Throws [VaultLockedException]. */
    fun portableKey(): PortableKey

    /**
     * A portable key able to open an envelope written with [salt]/[iterations]: the
     * vault's own key when they match, else a key adopted via [derivePortableKey] in
     * this process; null when the user must supply the source password.
     */
    fun portableKeyFor(
        salt: ByteArray,
        iterations: Int,
    ): PortableKey?

    /**
     * Derives the portable key of a FOREIGN envelope — a backup or Drive file written
     * under a different password/salt. Pure derivation; call [adoptPortableKey] once
     * it has been PROVEN right (a successful decryption) so later reads are silent.
     */
    suspend fun derivePortableKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
    ): PortableKey

    /** Remembers a verified foreign [key] for the process lifetime (never persisted). */
    fun adoptPortableKey(key: PortableKey)

    // ---- Biometric unlock (wraps the DEK with a Keystore-held, auth-gated key) ----

    /**
     * Enables biometric unlock: [authenticatedCipher] is the ENCRYPT-mode cipher the
     * user just authenticated through BiometricPrompt (from [BiometricKeyWrapper]).
     */
    suspend fun enableBiometric(authenticatedCipher: Cipher)

    suspend fun disableBiometric()

    /** Stored IV of the biometric wrap, needed to init the DECRYPT cipher; null when disabled. */
    fun biometricWrapIv(): ByteArray?

    /** Unlocks with the DECRYPT-mode cipher the user just authenticated. */
    suspend fun unlockWithBiometric(authenticatedCipher: Cipher): Boolean

    // ---- One-time storage migration bookkeeping ----

    fun filesMigrated(): Boolean

    suspend fun markFilesMigrated()

    companion object {
        const val DB_KEY_LABEL = "cleartravel/db/v1"
        const val FILE_KEY_LABEL = "cleartravel/files/v1"
    }
}

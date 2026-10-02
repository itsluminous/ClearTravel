package com.itsluminous.cleartravel.core.security.vault

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The persisted key file (ADR-031): the random master Data Encryption Key (DEK) is
 * NEVER stored raw — only wrapped, twice at most:
 *
 * - [passwordWrap]: AES-GCM under a KEK derived from the user's password
 *   (PBKDF2-HMAC-SHA256, [salt] + [iterations]); the 64-byte PBKDF2 output is split
 *   into the KEK (first 32 bytes) and the *portable key* (last 32 bytes) that seals
 *   backups and Drive uploads — one derivation per unlock covers both.
 * - [biometricWrap]: AES-GCM under an Android Keystore key that requires biometric
 *   authentication per use; null while biometric unlock is disabled.
 * - [portableWrap]: the portable key sealed under a DEK-derived wrapping key, so a
 *   BIOMETRIC unlock (no password in hand) can still export backups and upload to
 *   Drive. Circular only in appearance: the portable key is protected by the DEK,
 *   which is protected by the password / biometric key.
 * - [backgroundWrap] (ADR-043, file version 2, OPT-IN): AES-GCM under an Android
 *   Keystore key that needs NO user authentication (device-unlocked required, StrongBox
 *   when available), so background workers can open the store before the user has
 *   unlocked the app in this process. Null — the default — means the ADR-031 model
 *   holds unchanged: the DEK exists in memory only after a password/biometric unlock.
 *
 * Version history: 1 = password + optional biometric + portable wraps (ADR-031 files
 * carry no `version` key at all — the default on decode is 1); 2 = adds the optional
 * [backgroundWrap] and always writes `version`. Readers ignore unknown keys, so a v1
 * build opens a v2 file (it simply never uses the extra slot) and a v2 build reads v1
 * (slot absent).
 *
 * The file sits in plain app storage BY DESIGN — like a password-manager database,
 * its confidentiality rests entirely on the password. Forgetting the password loses
 * the data (there is no recovery key); this is the documented trade-off.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VaultFile(
    /** Highest format the writer knew: ADR-031 files carry no key (decodes as 1); ADR-043 writes 2. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = 1,
    val salt: ByteArray,
    val iterations: Int,
    val passwordWrap: WrappedKey,
    val portableWrap: WrappedKey,
    val biometricWrap: WrappedKey? = null,
    /** One-time plaintext→encrypted storage migration completed (files + local backups). */
    val filesMigrated: Boolean = false,
    /** ADR-043: the opt-in background-sync wrap; null while the setting is off. */
    val backgroundWrap: WrappedKey? = null,
) {
    companion object {
        const val VERSION = 2
    }
}

/** An AES-GCM sealed 32-byte key: [iv] (12 bytes) + [ciphertext] (32 + 16 tag). */
@Serializable
data class WrappedKey(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/** Where the [VaultFile] lives — a real file in production, memory in tests/e2e. */
interface KeyFileStore {
    fun read(): VaultFile?

    fun write(file: VaultFile)
}

/** `filesDir/security/vault.json`, written atomically (temp + rename). */
class FileKeyFileStore(
    private val file: File,
) : KeyFileStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): VaultFile? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString<VaultFile>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    override fun write(file: VaultFile) {
        val target = this.file
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(json.encodeToString(VaultFile.serializer(), file))
        if (!temp.renameTo(target)) {
            // Cross-device or a stale target: fall back to a copy.
            temp.copyTo(target, overwrite = true)
            if (!temp.delete()) throw IOException("could not replace key file")
        }
    }

    companion object {
        const val DIRECTORY_NAME = "security"
        const val FILE_NAME = "vault.json"

        fun default(filesDir: File): FileKeyFileStore = FileKeyFileStore(File(File(filesDir, DIRECTORY_NAME), FILE_NAME))
    }
}

/** Pure in-memory store for unit tests and the hermetic e2e suite. */
class InMemoryKeyFileStore(
    initial: VaultFile? = null,
) : KeyFileStore {
    var current: VaultFile? = initial
        private set

    override fun read(): VaultFile? = current

    override fun write(file: VaultFile) {
        current = file
    }
}

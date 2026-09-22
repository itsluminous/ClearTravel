package com.itsluminous.cleartravel.core.security.vault

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultKeyVaultTest {
    private val store = InMemoryKeyFileStore()

    // Tests use a low iteration count — the KDF's strength is not under test, its wiring is.
    private fun vault(): DefaultKeyVault = DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher())

    @Test
    fun freshStore_isNotSetUp_andKeysAreUnavailable() {
        val vault = vault()
        assertThat(vault.state.value).isEqualTo(VaultState.NotSetUp)
        assertThrows(VaultLockedException::class.java) { vault.fileKey() }
        assertThrows(VaultLockedException::class.java) { vault.databaseKey() }
        assertThrows(VaultLockedException::class.java) { vault.portableKey() }
    }

    @Test
    fun setUp_unlocks_andNeverStoresTheDekRaw() =
        runTest {
            val vault = vault()
            vault.setUp("correct horse".toCharArray())

            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = false))
            val file = store.current!!
            val fileKey = vault.fileKey().encoded
            val dbKey = vault.databaseKey()
            assertThat(fileKey).hasLength(32)
            assertThat(dbKey).hasLength(32)
            assertThat(fileKey).isNotEqualTo(dbKey)
            // Only wrapped material is persisted: each blob is key + GCM tag, no raw key.
            assertThat(file.passwordWrap.ciphertext).hasLength(32 + 16)
            assertThat(file.portableWrap.ciphertext).hasLength(32 + 16)
            assertThat(file.biometricWrap).isNull()
            assertThat(file.filesMigrated).isTrue()
        }

    @Test
    fun unlockWithPassword_roundTrip_rejectsWrongPassword_andKeepsKeysStable() =
        runTest {
            val first = vault()
            first.setUp("pw-one".toCharArray())
            val fileKey = first.fileKey().encoded
            val portable = first.portableKey().key.encoded

            val second = vault() // new process over the same key file
            assertThat(second.state.value).isEqualTo(VaultState.Locked(biometricEnabled = false))
            assertThat(second.unlockWithPassword("nope".toCharArray())).isFalse()
            assertThat(second.state.value).isEqualTo(VaultState.Locked(biometricEnabled = false))

            assertThat(second.unlockWithPassword("pw-one".toCharArray())).isTrue()
            assertThat(second.fileKey().encoded).isEqualTo(fileKey)
            assertThat(second.portableKey().key.encoded).isEqualTo(portable)
        }

    @Test
    fun changePassword_reWrapsOnly_sameDek_newPortableSalt() =
        runTest {
            val vault = vault()
            vault.setUp("old".toCharArray())
            val fileKey = vault.fileKey().encoded
            val oldPortable = vault.portableKey()

            assertThat(vault.changePassword("wrong".toCharArray(), "new".toCharArray())).isFalse()
            assertThat(vault.changePassword("old".toCharArray(), "new".toCharArray())).isTrue()

            assertThat(vault.fileKey().encoded).isEqualTo(fileKey) // no data re-encryption needed
            val newPortable = vault.portableKey()
            assertThat(newPortable.salt).isNotEqualTo(oldPortable.salt)
            assertThat(newPortable.key.encoded).isNotEqualTo(oldPortable.key.encoded)

            val reopened = vault()
            assertThat(reopened.unlockWithPassword("old".toCharArray())).isFalse()
            assertThat(reopened.unlockWithPassword("new".toCharArray())).isTrue()
            assertThat(reopened.fileKey().encoded).isEqualTo(fileKey)
        }

    @Test
    fun portableKeyFor_matchesOwnSalt_elseNeedsDerivation_thenAdopts() =
        runTest {
            val vault = vault()
            vault.setUp("pw".toCharArray())
            val own = vault.portableKey()
            assertThat(vault.portableKeyFor(own.salt, own.iterations)).isSameInstanceAs(own)

            val foreignSalt = CryptoPrimitives.randomBytes(16)
            assertThat(vault.portableKeyFor(foreignSalt, 1_000)).isNull()

            val derived = vault.derivePortableKey("other-password".toCharArray(), foreignSalt, 1_000)
            assertThat(vault.portableKeyFor(foreignSalt, 1_000)).isSameInstanceAs(derived)
            // Same password + same salt on another vault yields the same portable key.
            val other = DefaultKeyVault(InMemoryKeyFileStore(), iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher())
            val again = other.derivePortableKey("other-password".toCharArray(), foreignSalt, 1_000)
            assertThat(again.key.encoded).isEqualTo(derived.key.encoded)
        }

    @Test
    fun biometric_enableUnlockDisable_roundTrip() =
        runTest {
            val vault = vault()
            vault.setUp("pw".toCharArray())
            val fileKey = vault.fileKey().encoded

            // Stand-in for the Keystore key: an ordinary AES key behind GCM ciphers.
            val keystoreKey = CryptoPrimitives.randomKey()
            val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey) }
            vault.enableBiometric(encrypt)
            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = true))
            assertThat(vault.biometricWrapIv()).isEqualTo(encrypt.iv)

            val reopened = vault()
            assertThat(reopened.state.value).isEqualTo(VaultState.Locked(biometricEnabled = true))
            val decrypt =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(128, reopened.biometricWrapIv()))
                }
            assertThat(reopened.unlockWithBiometric(decrypt)).isTrue()
            assertThat(reopened.fileKey().encoded).isEqualTo(fileKey)
            // Biometric unlock still yields the portable key (DEK-wrapped copy).
            assertThat(reopened.portableKey().key.encoded).isEqualTo(vault.portableKey().key.encoded)

            reopened.disableBiometric()
            assertThat(reopened.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = false))
            assertThat(reopened.biometricWrapIv()).isNull()
        }

    @Test
    fun filesMigratedFlag_isOneWay() =
        runTest {
            // A pre-existing (migrated-from-plaintext) install is simulated by clearing the flag.
            val vault = vault()
            vault.setUp("pw".toCharArray())
            store.write(store.current!!.copy(filesMigrated = false))
            assertThat(vault.filesMigrated()).isFalse()
            vault.markFilesMigrated()
            assertThat(vault.filesMigrated()).isTrue()
            vault.markFilesMigrated()
            assertThat(vault.filesMigrated()).isTrue()
        }
}

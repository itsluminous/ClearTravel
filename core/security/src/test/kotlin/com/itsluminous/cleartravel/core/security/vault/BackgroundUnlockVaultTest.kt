package com.itsluminous.cleartravel.core.security.vault

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.security.background.FakeBackgroundKeyWrapper
import com.itsluminous.cleartravel.core.security.background.NoBackgroundKeyWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ADR-043: the opt-in background-sync slot of the vault, over a fake Keystore. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundUnlockVaultTest {
    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private val store = InMemoryKeyFileStore()
    private val keystore = FakeBackgroundKeyWrapper()

    private fun vault(wrapper: FakeBackgroundKeyWrapper = keystore): DefaultKeyVault =
        DefaultKeyVault(store, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = wrapper)

    @Test
    fun enable_requiresTheRightPassword_andWritesTheSlotAsFileVersion2() =
        runTest {
            val vault = vault()
            vault.setUp("pw".toCharArray())
            assertThat(vault.hasBackgroundKey).isFalse()
            assertThat(store.current!!.backgroundWrap).isNull()

            assertThat(vault.enableBackgroundUnlock("wrong".toCharArray())).isFalse()
            assertThat(store.current!!.backgroundWrap).isNull()
            assertThat(keystore.creations).isEqualTo(0)

            assertThat(vault.enableBackgroundUnlock("pw".toCharArray())).isTrue()
            assertThat(vault.hasBackgroundKey).isTrue()
            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = false, backgroundUnlockEnabled = true))
            val file = store.current!!
            assertThat(file.version).isEqualTo(2)
            assertThat(file.backgroundWrap!!.ciphertext).hasLength(32 + 16) // wrapped DEK, never raw
            assertThat(keystore.creations).isEqualTo(1)
        }

    @Test
    fun coldProcess_unlocksWithTheBackgroundKey_sameDek_uiStateStaysLocked() =
        runTest {
            val first = vault()
            first.setUp("pw".toCharArray())
            first.enableBackgroundUnlock("pw".toCharArray())
            val fileKey = first.fileKey().encoded
            val dbKey = first.databaseKey()

            val cold = vault() // new process, same key file + same Keystore
            assertThat(cold.state.value).isEqualTo(VaultState.Locked(biometricEnabled = false, backgroundUnlockEnabled = true))
            assertThat(cold.isUnlocked).isFalse()

            assertThat(cold.unlockWithBackgroundKey()).isTrue()
            assertThat(cold.isUnlocked).isTrue()
            assertThat(cold.fileKey().encoded).isEqualTo(fileKey)
            assertThat(cold.databaseKey()).isEqualTo(dbKey)
            // The portable key comes back too (DEK-wrapped copy) — backups can run.
            assertThat(cold.portableKey().key.encoded).isEqualTo(first.portableKey().key.encoded)
            // Idempotent while already unlocked.
            assertThat(cold.unlockWithBackgroundKey()).isTrue()
        }

    @Test
    fun absentSlot_orSettingOff_neverUnlocks() =
        runTest {
            val vault = vault()
            vault.setUp("pw".toCharArray())
            val cold = vault()
            assertThat(cold.unlockWithBackgroundKey()).isFalse()
            assertThat(cold.isUnlocked).isFalse()

            // A vault wired without the opt-in (the default) can never create or use a slot.
            val plain =
                DefaultKeyVault(
                    store,
                    iterations = 1_000,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    backgroundKeyWrapper = NoBackgroundKeyWrapper,
                )
            assertThat(plain.unlockWithBackgroundKey()).isFalse()
            assertThat(plain.hasBackgroundKey).isFalse()
        }

    @Test
    fun disable_removesTheSlot_andDeletesTheKeystoreKey() =
        runTest {
            val vault = vault()
            vault.setUp("pw".toCharArray())
            vault.enableBackgroundUnlock("pw".toCharArray())
            assertThat(keystore.hasKey).isTrue()

            vault.disableBackgroundUnlock()
            assertThat(vault.hasBackgroundKey).isFalse()
            assertThat(store.current!!.backgroundWrap).isNull()
            assertThat(keystore.hasKey).isFalse()
            assertThat(keystore.deletions).isEqualTo(1)
            assertThat(vault.isUnlocked).isTrue() // the current session is untouched

            assertThat(vault().unlockWithBackgroundKey()).isFalse()
        }

    @Test
    fun deviceLocked_orKeystoreKeyGone_orCorruptSlot_failsClosed() =
        runTest {
            val vault = vault()
            vault.setUp("pw".toCharArray())
            vault.enableBackgroundUnlock("pw".toCharArray())

            keystore.deviceLocked = true
            assertThat(vault().unlockWithBackgroundKey()).isFalse()
            keystore.deviceLocked = false
            assertThat(vault().unlockWithBackgroundKey()).isTrue()

            // Keystore key gone (factory reset of the Keystore, key invalidated): fail closed.
            val slot = store.current!!.backgroundWrap!!
            keystore.deleteKey()
            assertThat(vault().unlockWithBackgroundKey()).isFalse()

            // Corrupt / foreign slot bytes under a valid key: GCM tag failure, fail closed.
            vault.enableBackgroundUnlock("pw".toCharArray())
            store.write(store.current!!.copy(backgroundWrap = slot))
            val corrupt = vault()
            assertThat(corrupt.unlockWithBackgroundKey()).isFalse()
            assertThat(corrupt.isUnlocked).isFalse()
            store.write(store.current!!.copy(backgroundWrap = slot.copy(ciphertext = slot.ciphertext.copyOf(10))))
            assertThat(vault().unlockWithBackgroundKey()).isFalse()
            // The password path is unaffected by a broken slot.
            assertThat(vault().unlockWithPassword("pw".toCharArray())).isTrue()
        }

    @Test
    fun changePassword_keepsTheBackgroundSlotValid() =
        runTest {
            val vault = vault()
            vault.setUp("old".toCharArray())
            vault.enableBackgroundUnlock("old".toCharArray())
            val fileKey = vault.fileKey().encoded
            val slotBefore = store.current!!.backgroundWrap!!

            assertThat(vault.changePassword("old".toCharArray(), "new".toCharArray())).isTrue()
            assertThat(store.current!!.backgroundWrap).isEqualTo(slotBefore) // wraps the DEK, not the KEK

            val cold = vault()
            assertThat(cold.hasBackgroundKey).isTrue()
            assertThat(cold.unlockWithBackgroundKey()).isTrue()
            assertThat(cold.fileKey().encoded).isEqualTo(fileKey)
        }

    @Test
    fun biometricAndBackgroundSlots_areIndependent() =
        runTest {
            val vault = vault()
            vault.setUp("pw".toCharArray())
            vault.enableBackgroundUnlock("pw".toCharArray())
            vault.disableBiometric() // no-op on the background slot
            assertThat(vault.hasBackgroundKey).isTrue()
            assertThat(vault.state.value).isEqualTo(VaultState.Unlocked(biometricEnabled = false, backgroundUnlockEnabled = true))
        }

    @Test
    fun keyFile_v1WithoutTheSlot_readsAsVersion1_andV2RoundTripsThroughJson() =
        runTest {
            val fileStore = FileKeyFileStore(File(tmp.root, "vault.json"))
            val v1 =
                DefaultKeyVault(fileStore, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = keystore)
            v1.setUp("pw".toCharArray())
            // Simulate a file written by an ADR-031 build: no version key, no slot key.
            val text = File(tmp.root, "vault.json").readText()
            assertThat(text).contains("\"version\":2")
            assertThat(text).doesNotContain("backgroundWrap") // null defaults are not encoded
            File(tmp.root, "vault.json").writeText(text.replace("\"version\":2,", ""))
            val readBack = fileStore.read()!!
            assertThat(readBack.version).isEqualTo(1)
            assertThat(readBack.backgroundWrap).isNull()

            val reopened =
                DefaultKeyVault(fileStore, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = keystore)
            assertThat(reopened.unlockWithBackgroundKey()).isFalse()
            assertThat(reopened.enableBackgroundUnlock("pw".toCharArray())).isTrue()
            val v2 = fileStore.read()!!
            assertThat(v2.version).isEqualTo(2)
            assertThat(v2.backgroundWrap).isNotNull()
            assertThat(
                DefaultKeyVault(fileStore, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = keystore)
                    .unlockWithBackgroundKey(),
            ).isTrue()
        }
}

# core:security

The at-rest encryption and app-lock primitives (ADR-031). Sits BELOW `core:data`: it
knows nothing about Room, repositories or features, and is pure JVM except for the two
Android Keystore / BiometricPrompt wrappers.

| Piece | Role |
|---|---|
| `vault/KeyVault` (`DefaultKeyVault`) | One random 256-bit DEK per install, persisted only WRAPPED in `vault.json`: password wrap (PBKDF2-HMAC-SHA256, 210k iterations → KEK + portable key), optional biometric wrap (Keystore key, auth-required), portable-key wrap. `setUp` / `unlock` / `unlockWithBiometric` / `changePassword` (re-wrap only) / `enableBiometric` / `disableBiometric`; sub-keys `databaseKey()` and `fileKey()` are HMAC derivations, never the DEK. `VaultState` = `NotSetUp` / `Locked` / `Unlocked`. |
| `crypto/LocalFileCipher` | CTEF v1: chunked AES-256-GCM (64 KiB chunks, `nonce ‖ index` IVs, header + `isLast` as AAD). Reads without the magic pass through as plaintext (migration + tolerance), writes are always encrypted. |
| `crypto/PortableCipher` | CTEB v1: the password-derived envelope for backups and Drive uploads — restores on any install that knows the password (`docs/backup-format.md`). |
| `biometric/BiometricKeyWrapper` + `BiometricUnlock` | Keystore key creation/wrapping and the `BiometricPrompt` host (BIOMETRIC_STRONG only, `CryptoObject`-bound). `findActivity(context)` is also used by `feature:applock`'s `SecureWindowEffect`. |
| `lock/AppLockController` + `LockTiming` | The UI half of the lock: re-lock after the configured background time (default 1 minute, ADR-034); `LockTiming.securesWindow` marks *Immediately* as the `FLAG_SECURE` timing (ADR-036). The DEK stays cached across a UI re-lock so background jobs keep running. |
| `di/SecurityModule` | Hilt bindings; the hermetic e2e replaces it with `TestSecurityModule` (pre-unlocked in-memory vault, plain-AES fake wrapper). |

Never persisted: the DEK unwrapped, any sub-key, the password. There is deliberately no
recovery path — a forgotten password loses the data.

Tests (`src/test`): chunked AEAD tamper cases (reorder / drop / truncate / wrong key),
vault set-up/unlock/change/biometric wraps with a fake wrapper, envelope round trips,
lock timing. Use `DefaultKeyVault(InMemoryKeyFileStore(), iterations = 1_000)` in other
modules' tests — KDF strength is not what they test.

# feature:applock

The gate composed around the whole app shell (`AppLockGate`, ADR-031/032/034/036).
`AppLockViewModel` derives one `AppLockUiState` from the vault state, the UI lock, the
storage-preparation flag and the onboarding flag:

| State | Screen |
|---|---|
| `Setup` | first run, wizard step 1 — `SetupPasswordScreen`: password + confirmation (`ContentType.NewPassword` autofill), live strength hint, data-loss warning, fingerprint toggle (enrols inside `BiometricPrompt` right after the vault is created; a dismissed prompt continues without biometrics) |
| `Locked` | `UnlockScreen`: password (`ContentType.Password`) or the auto-shown `BiometricPrompt`; an invalidated Keystore key falls back to the password |
| `Preparing` | "Securing your data…" while `SecureStorageInitializer` opens the encrypted database and runs the one-time plaintext → SQLCipher/CTEF migration |
| `Onboarding` | wizard steps 2–4 (`onboarding/OnboardingWizard`): connect Google for Drive backups or stay offline → restore from a file / from Drive or start fresh → the backup's own password when it differs. Gated on `SettingsRepository.onboardingPending` |
| `Ready` | the app; `onUnlocked` fires once (the shell runs startup housekeeping and the one-time notification-permission request THERE, never above the gate) |

Also here: `AppLockLifecycleObserver` (process-lifecycle re-lock after the configured
`LockTiming`) and `SecureWindowEffect` (`FLAG_SECURE` on the activity window while the
lock timing is *Immediately*).

Depends on `core:data` (settings, storage initializer), `core:security`, `core:google`
(step 2 linking + Drive listing) and `core:designsystem` — `core:*` only, like every
feature module. Strings are `applock_*`.

Tests: `AppLockViewModelTest` (state machine incl. the no-`Locked`-gap enrolment path
and the resume-after-death path), `OnboardingViewModelTest` (Robolectric: every step,
consent round trip, foreign-password restore). E2e:
`AppLockSetupE2eTest` (flip `TestSecurityModule.freshInstall`).

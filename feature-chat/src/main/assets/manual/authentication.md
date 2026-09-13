# Authentication & App Lock

keywords: authentication, app lock, security, biometric, BIOMETRIC_STRONG, device credential, BiometricPrompt, androidx.biometric, security key, hardware key, YubiKey, Thetis, CTAP2, FIDO2, yubikit, USB, NFC, SmartCardConnection, key presence, TOTP, RFC 6238, HmacSHA1, one-time password, otpauth, authenticator app, Base32, Android Keystore, KeystoreSecretBox, AES-256-GCM, StrongBox, EC P-256, secp256r1, SHA256withECDSA, KeystoreIdentity, AuthFactor, AuthRepository, LocalAuthRepository, AuthSettings, AuthState, AuthResult, Verifier, LocalVerifier, GatewayVerifier, AuthGate, AuthInterop, SecuritySettingsScreen, app-launch gate, grace window, GMS-free, no Google Play Services, opt-in, device-local

AIOPE ships an **optional, opt-in** authentication layer with three independent factors —
biometric/device-credential, an external hardware security key, and a TOTP authenticator —
plus an optional app-launch gate ("app lock"). Everything is **device-local** and uses **no
Google Play Services** (GMS): androidx.biometric for biometrics, Yubico's yubikit for hardware
keys, and the Android Keystore for key/secret storage. This page is written from the actual
Kotlin source. Primary files:

- `core-auth/.../AuthFactor.kt` — the `AuthFactor` enum, `AuthState`, and `AuthResult` (in `AuthState.kt`).
- `core-auth/.../AuthRepository.kt` — the repository interface (facade over the factors).
- `core-auth/.../LocalAuthRepository.kt` — the v1 device-local implementation.
- `core-auth/.../AuthSettings.kt` — persisted enabled-factor set + app-lock flag + account id.
- `core-auth/.../BiometricUnlock.kt` — the biometric / device-credential factor.
- `core-auth/.../SecurityKeyAuthenticator.kt` — the hardware-key (USB/NFC) factor.
- `core-auth/.../TotpEnrollment.kt` — RFC 6238 TOTP enrollment and verification.
- `core-auth/.../KeystoreSecretBox.kt` — AES-256-GCM sealing of the TOTP secret.
- `core-auth/.../KeystoreIdentity.kt` — the device-bound EC P-256 identity keypair.
- `core-auth/.../Verifier.kt`, `LocalVerifier.kt` — the verification seam and its v1 backing.
- `core-auth/.../di/AuthModule.kt` — Hilt wiring (all singletons).
- `feature-chat/.../settings/AuthGate.kt` — the Compose app-launch gate.
- `feature-chat/.../settings/SecuritySettingsScreen.kt` — the Security settings UI.
- `core-preferences/.../AuthInterop.kt` — the "don't re-lock during a system excursion" bridge.

## The three factors

The factors are defined by the `AuthFactor` enum (`AuthFactor.kt`). There are exactly **three**,
each with a stable `id` and a `displayName`:

- `BIOMETRIC` — id `"biometric"`, "Biometric unlock".
- `SECURITY_KEY` — id `"security_key"`, "Hardware security key".
- `TOTP` — id `"totp"`, "Authenticator app (TOTP)".

Per the enum's own documentation and the repository facade: **all factors are optional and
opt-in**; a user may enable none, one, or several. `AuthFactor.from(id)` resolves an id string
back to the enum (returns `null` on an unknown id), which is how the persisted set is decoded.

Enabling a factor is **not** by itself a gate — the `AuthRepository` doc states plainly that
"Enabling a factor is NOT a gate; the app remains fully usable without any factor." A factor only
blocks app entry when the separate app-lock flag is also on (see **App lock** below).

## State and results

`AuthState` (`AuthState.kt`) is the observable snapshot:

- `accountId: String` — stable per-account identifier.
- `enabledFactors: Set<AuthFactor>` — the factors turned on (default empty).
- `appLockEnabled: Boolean` — the app-lock preference (default `false`).

Derived helpers:

- `hasAnyFactor` — `enabledFactors.isNotEmpty()`.
- `isEnabled(factor)` — membership test.
- `gateActive` — **`appLockEnabled && hasAnyFactor`**. This is the crucial rule: the app-launch
  gate is only effective when the user opted in **and** at least one factor is enrolled.

`AuthResult` is a sealed interface with these cases: `Success`, `Enrolled(secret, otpauthUri)`
(TOTP provisioning material), `Cancelled` (user dismissed / removed key mid-flow), `Unavailable(reason)`
(factor not usable on this device), and `Failure(reason, cause)`.

## AuthRepository / LocalAuthRepository

`AuthRepository` is the facade; `LocalAuthRepository` is the only implementation in this version
("v1 device-local"). It composes `AuthSettings`, `KeystoreIdentity`, `BiometricUnlock`,
`TotpEnrollment`, and `SecurityKeyAuthenticator`, and exposes a `StateFlow<AuthState>` built from
a `snapshot()` of settings; `refresh()` re-emits after every mutation.

Key methods and their **actual** behavior:

- `isAvailable(factor)` — `BIOMETRIC` → always `true` (the code comments that real availability is
  confirmed against the activity at enroll time), `SECURITY_KEY` → `securityKey.isAvailable()`,
  `TOTP` → always `true`.
- `enroll(activity, factor)` — first calls `identity.ensureKeyPair()` so the identity key exists,
  then runs the factor-specific enroll flow. Marks the factor enabled **only** on `Success` or
  `Enrolled`, then refreshes.
- `verify(activity, factor)` — returns `Failure("Factor not enrolled")` if the factor isn't
  enabled. For `BIOMETRIC` and `SECURITY_KEY` it re-runs the prompt; for `TOTP` it returns
  `Success` immediately because "Code entry handled by the UI, then passed to verifyTotpCode()."
- `disable(factor)` — for `TOTP` also calls `totp.disable()` (forgets the sealed secret), then
  clears the enabled flag and refreshes.
- `setAppLock(enabled)` — writes the app-lock flag and refreshes.
- `verifyTotpCode(code)` — delegates to `TotpEnrollment.verify`.

## Biometric / device-credential factor

`BiometricUnlock` uses `androidx.biometric` and, per its own KDoc, "Contains no Google Play
Services dependency." The allowed authenticators are
`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, i.e. a strong biometric with fallback to the device
credential (PIN / pattern / password).

- `isAvailable(activity)` — `true` only when `BiometricManager.canAuthenticate(...)` returns
  `BIOMETRIC_SUCCESS`.
- `authenticate(activity, title, subtitle)` — a suspending call that presents `BiometricPrompt`
  and resolves to an `AuthResult`. It re-checks availability first (resolving `Unavailable` with
  the numeric code if not available). `ERROR_USER_CANCELED`, `ERROR_NEGATIVE_BUTTON`, and
  `ERROR_CANCELED` map to `Cancelled`; other errors map to `Failure`. A single non-match
  (`onAuthenticationFailed`) intentionally does **not** resolve — the prompt stays up.

## Hardware security key factor — honest scope

`SecurityKeyAuthenticator` uses Yubico's **yubikit** (`YubiKitManager`) over USB or NFC, and the
KDoc notes it is "Apache-2.0, no Google Play Services." It targets any external key (YubiKey /
Thetis / any CTAP2 key).

**Important — this factor only confirms key *presence and connectability*, not a full FIDO2/CTAP2
ceremony.** This is stated directly in the source:

> "v1 scope: confirm a hardware key is present and connectable — this is the meaningful
> 'possession' proof for a device-local verifier. The full FIDO2/CTAP2 make/get-credential
> ceremony belongs with a server RP and is exposed via the [Verifier] seam."

Concretely:

- `isAvailable()` reports whether the **device** has a USB host or NFC (`FEATURE_USB_HOST` or
  `FEATURE_NFC`) — **not** whether a key is plugged in.
- `awaitKeyPresence(activity, enableNfc = true)` starts USB discovery (`UsbConfiguration`) and,
  if enabled, NFC discovery (`NfcConfiguration().timeout(20000)`, i.e. a 20-second NFC window),
  and waits for the first key it can open a `SmartCardConnection` to. Successfully opening the
  connection is treated as "key present & usable" and resolves `Success`; a connection error
  resolves `Failure`. If neither transport can start it resolves `Unavailable`. Cancellation
  resolves `Cancelled` and stops both discoveries.
- No challenge is signed and no assertion is generated by this class. There is **no** CTAP2
  make-credential or get-assertion here — only that a card connection could be opened. A source
  note also warns that a phone's USB port is occupied by the adb cable during tethered testing,
  so NFC tap is the way to test presence.

## TOTP authenticator factor

`TotpEnrollment` implements **RFC 6238** TOTP with these fixed parameters (from the source):
`algorithm = "HmacSHA1"`, `digits = 6`, `period = 30` (seconds). It is described as "fully local":
the secret is generated, sealed, and verified on-device.

- Storage: a `SharedPreferences` file named **`"aiope_auth"`**, under the key
  `"totp_sealed_secret"`. The value stored is the **ciphertext** of the secret, sealed by
  `KeystoreSecretBox` (see below) — the raw seed is not persisted in plaintext.
- `enroll(accountLabel = "AIOPE")` — generates a **random 20-byte** secret via `SecureRandom`,
  Base32-encodes it, seals and stores it, and returns an `Enrollment(secret, otpauthUri)`.
  It overwrites any prior secret. The provisioning URI is a standard
  `otpauth://totp/<issuer:account>?secret=...&issuer=AIOPE&algorithm=SHA1&digits=6&period=30`
  with issuer **"AIOPE"**, so the seed can be imported into a standalone authenticator app
  (Aegis, Google Authenticator, etc.) by QR or manual entry.
- `verify(code, atMillis)` — opens the sealed secret, normalizes the entered code (trim + strip
  spaces), and accepts a **±1 time-step** window (so the previous, current, and next 30-second
  codes are valid) to tolerate clock skew.
- `currentCode(atMillis)` — returns the current code for a live "it works" display during setup.
- `isEnrolled()` / `disable()` — presence check / removal of the stored secret.
- The `LocalAuthRepository` seeds the `otpauth` account label with the current `accountId()`,
  not the literal default.

## Keystore-backed storage

**`KeystoreSecretBox`** seals small secrets (the TOTP seed) with an **AES-256-GCM** key held in
the `AndroidKeyStore` (alias `aiope_auth_secret_wrap_aes`). The wrapping key never leaves secure
hardware; ciphertext is stored as `base64(iv || ct)` with a 12-byte IV and a 128-bit tag.

**`KeystoreIdentity`** manages the account's device-bound identity keypair: an **EC P-256**
(`secp256r1`) keypair in the `AndroidKeyStore` (alias `aiope_account_identity_ec_p256`), signing
with **`SHA256withECDSA`**. It requests **StrongBox** when supported and automatically falls back
if the device advertises but rejects it. The base identity key is created with
`requireUserAuth = false` so it exists before any factor is enrolled; the KDoc notes factor-specific
keys can opt into `setUserAuthenticationRequired(true)` (bound to `AUTH_BIOMETRIC_STRONG` /
`AUTH_DEVICE_CREDENTIAL`, and invalidated by new biometric enrollment). The public key is
exportable as base64 X.509 SubjectPublicKeyInfo — "the form a server RP would store."

## The verification seam (device-local today)

`Verifier` is an interface — "the verification seam" — with `challenge(...)`, `verify(...)`, and a
boolean `isServerBacked`. The **only** implementation wired in is `LocalVerifier`, whose
`isServerBacked` is **`false`**.

The source is explicit and honest about the trust model:

> "In v1 that party is the device itself ([LocalVerifier]) — this gives a real Keystore/hardware-backed
> UX and a stable identity, but NOT server-grade abuse resistance, because the verifier runs on the
> same (attackable) device."

`LocalVerifier.challenge(...)` returns 32 random bytes. `verify(...)` checks `BIOMETRIC` and
`SECURITY_KEY` proofs as a `KeystoreIdentity` signature over the challenge, and `TOTP` as the ASCII
code bytes. The KDoc describes a future `GatewayVerifier` (against an operator-controlled backend)
that could implement the same interface "without changing callers" — but that server-backed verifier
is **not present in this codebase**; treat all verification here as device-local.

## App lock (the app-launch gate)

The gate is `AuthGate` (a Compose wrapper in `feature-chat/.../settings/AuthGate.kt`), and it **is**
wired into app startup: `AiopeMain` renders the whole navigation host inside `AuthGate { ... }` after
the splash screen. So the gate is really enforced, subject to `gateActive`.

Behavior in the source:

- If `state.gateActive` is `false` (user didn't opt in, or no factor enrolled), `content()` renders
  immediately — nothing is blocked.
- When active, a "AIOPE is locked" screen is shown with a button per enrolled factor:
  - **Biometric** auto-triggers on entry (it has its own system UI) and also has an "Unlock with
    biometrics" button.
  - **Security key** requires an explicit tap of "Unlock with security key" (it then calls
    `awaitKeyPresence` and shows a "Tap your key…"/progress hint).
  - **TOTP** shows a 6-digit field (digits only, capped at 6) and an "Unlock with code" button that
    calls `verifyTotpCode`.
- **Re-lock on background:** a lifecycle observer records when the app is stopped and re-locks on
  return only if backgrounded longer than a **3-second grace window** (`GRACE_MS = 3000L`). An
  in-flight biometric/security-key prompt (`authInFlight`) and `AuthInterop.active()` both suppress
  re-locking, so the system biometric UI or a file picker briefly covering the activity does not
  count as "backgrounded".

`AuthInterop` (in `core-preferences`) is the bridge that in-app flows use to say "I'm about to leave
the app briefly, don't re-lock": `begin()` / `end()` set a `suppressLock` flag, and `active()` stays
true within a **4-second return grace** (`returnGraceMs = 4000L`) after `end()`. The Security screen
calls `AuthInterop.begin()` before launching the assistant-role and overlay-permission system
intents.

## Settings UI

`SecuritySettingsScreen` (title "Security") presents a `FactorRow` toggle for each of the three
factors, a TOTP setup card (shows the Base32 secret grouped in 4s, plus "Copy secret" / "Copy
otpauth link", and a verify box), and the app-lock switch:

- The app-lock `ListItem` ("Require authentication to open the app") is **disabled until at least
  one factor is enrolled** (`enabled = state.hasAnyFactor`), matching `gateActive`.
- Enrolling a factor requires the screen to be hosted in a `FragmentActivity`; if not, it reports a
  status message rather than enrolling (biometric and security-key prompts need the activity).
- Security-key enrollment shows a cancelable "Insert or tap your security key" dialog with progress;
  Cancel cancels the coroutine job.
- The screen also carries two adjacent (non-auth) toggles — "Set AIOPE as device assistant" and
  "Floating voice button" — which use `AuthInterop.begin()/end()` around their system-intent
  excursions.
- The header text tells users the honest model: "Verification is on-device in this version; a future
  update can verify against a server for stronger abuse resistance."

## Dependency wiring

`AuthModule` (Hilt, `@InstallIn(SingletonComponent::class)`) provides everything as `@Singleton`:
`KeystoreIdentity`, `AuthSettings` (which also takes `Preferences`), `TotpEnrollment`,
`BiometricUnlock`, `SecurityKeyAuthenticator`, a `Verifier` bound to `LocalVerifier`, and an
`AuthRepository` bound to `LocalAuthRepository`. The feature module reaches the repository through a
Hilt `@EntryPoint` (`AuthEntryPoint` / `authRepository(context)`), not constructor injection, so the
Compose screens can obtain it from a plain `Context`.

## Account identity and migration

`AuthSettings.accountId()` returns a stable id stored under `"account_id"` in the `"aiope_auth"`
preferences. On first read it **migrates** from the legacy anonymous `Preferences.userUUID` "so that
any data already associated with the device carries over when auth factors are introduced." The
enabled-factor set is stored under `"enabled_factors"` as a string set of factor ids; `setEnabled`
deliberately writes a brand-new `HashSet` and uses `commit()` (not `apply()`) so the value is durable
before the state is re-read. The app-lock flag is `"app_lock_enabled"`.

## Honest limitations & scope

- **Verification is device-local.** The only `Verifier` is `LocalVerifier` with
  `isServerBacked = false`. There is a documented seam for a future `GatewayVerifier`, but no
  server-backed verifier exists in this codebase. The source itself states this gives hardware-backed
  UX but **not** server-grade abuse resistance.
- **The hardware-key factor is presence detection, not a FIDO2/CTAP2 ceremony.** It confirms a key
  can be connected over USB/NFC (`SmartCardConnection` opens) and nothing more; no challenge is signed
  or asserted by the key. The full make/get-credential flow is explicitly deferred to a server RP via
  the `Verifier` seam.
- **TOTP is standard but local.** RFC 6238, HMAC-SHA1, 6 digits, 30-second period, ±1 step skew. The
  seed is sealed with an AndroidKeyStore AES-GCM key, but the *sealed* value lives in the
  `"aiope_auth"` SharedPreferences file — protection rests on the Keystore wrapping key, not on the
  preferences file itself.
- **No Google Play Services anywhere in this stack** — androidx.biometric, yubikit, and AndroidKeyStore
  only. This is stated in the biometric and security-key sources.
- **Factors are opt-in and, by themselves, do not restrict the app.** Only the separate app-lock
  switch turns an enrolled factor into an entry gate, and only when `gateActive` (opted in **and** a
  factor enrolled). The gate re-locks on genuine backgrounding past a 3-second grace, with a 4-second
  interop return grace for system excursions.
- **`isAvailable` for biometric and TOTP always returns `true`** at the repository level; real
  biometric availability is only surfaced when the prompt runs (resolving `Unavailable` with a code).

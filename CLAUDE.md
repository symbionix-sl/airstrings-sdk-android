# AirStrings Android SDK

Kotlin library that fetches, verifies, caches, and exposes Ed25519-signed localized string bundles. Compose-first via `StateFlow` and `CompositionLocal`.

**Platform:** Android API 26+ | **Kotlin:** 2.0+ | **Dependencies:** OkHttp (networking), BouncyCastle (Ed25519), kotlinx-coroutines (StateFlow/async)

## Code Style

- **Indentation:** 4 spaces. No tabs.
- **Naming:** Kotlin conventions — `camelCase` for functions/properties, `PascalCase` for classes, `SCREAMING_SNAKE` for constants.
- **Trailing commas:** Always on multi-line declarations.
- **Explicit visibility:** All declarations have explicit visibility modifiers. No reliance on defaults.

## Non-Negotiables

Inherited from the parent project — these override everything else:

1. **Bundles are always signed.** No unsigned delivery path. Verification failure = hard error. Never display unverified strings.
2. **Signature verification order matters.** key_id lookup -> canonical JSON -> Ed25519 verify -> format_version check. Do not reorder.
3. **Re-verify on cache load.** Defense in depth — cached bundles are re-verified every time they're loaded from disk.
4. **Anti-downgrade.** Never replace a higher-revision bundle with a lower one for the same locale.
5. **Never crash, never block.** Network errors are silent. Signature failures reject the bundle but keep cached data. No cache + no network = key names as fallback.
6. **No secrets in source.** Public keys are provided by the integrator at init. Never hardcode, log, or embed keys.
7. **Tests accompany every deliverable.** No merge without tests covering the new behavior.

## Architecture

```
airstrings/src/main/kotlin/com/airstrings/sdk/
├── AirStrings.kt                # Public API — StateFlow-based observable state
├── AirStringsConfiguration.kt   # Init config (projectId, publicKeys, locale, baseURL)
├── AirStringsLocale.kt          # System | Fixed("en-US")
├── AirStringsError.kt           # Sealed class for errors
├── models/
│   ├── StringBundle.kt          # Data class bundle envelope (internal)
│   └── CanonicalJson.kt         # Deterministic serializer for signature verification
├── networking/
│   └── BundleFetcher.kt         # OkHttp with ETag/304 support
├── security/
│   ├── BundleVerifier.kt        # Ed25519 verification via BouncyCastle
│   └── Base64Url.kt             # RFC 4648 §5 codec
├── storage/
│   └── BundleStore.kt           # Disk cache in context.cacheDir/airstrings/
└── compose/
    └── AirStringsLocal.kt       # CompositionLocal for Jetpack Compose
```

### Layer Rules

| Layer | May depend on | Never depends on |
|-------|---------------|-------------------|
| `models/` | Kotlin stdlib, `org.json` | Networking, Storage, Security |
| `security/` | Models, Kotlin stdlib, BouncyCastle | Networking, Storage |
| `networking/` | Kotlin stdlib, OkHttp | Security, Storage, Models |
| `storage/` | Kotlin stdlib, `org.json` | Security, Networking, Models |
| `compose/` | AirStrings (top-level class), Compose runtime | Everything else |
| `AirStrings.kt` | All internal layers | Nothing depends on it |

OkHttp is isolated to `networking/BundleFetcher.kt`. No other file imports OkHttp.
BouncyCastle is isolated to `security/BundleVerifier.kt`. No other file imports BouncyCastle.

### Data Flow

```
CDN -> BundleFetcher (raw ByteArray) -> JSONObject (StringBundle) -> BundleVerifier -> BundleStore (save) -> AirStrings.strings (StateFlow)
```

Every step is a distinct responsibility. Data flows in one direction. The `AirStrings` class orchestrates but delegates all work.

## Security Rules

These are hard constraints. Violating any of them is a security bug.

- **Canonical JSON must be byte-identical across platforms.** The serializer in `CanonicalJson.kt` is the source of truth. Keys sorted lexicographically at every level. No whitespace. Integers as integers. RFC 8259 string escaping only. Any change requires updating the contract in `docs/contracts/bundle-format.md` and testing against the backend's output.
- **Signature covers metadata.** format_version, project_id, locale, revision, created_at are all in the signed content. This prevents bundle substitution, locale swaps, and downgrade attacks.
- **Unknown key_id = reject entirely.** Do not fall back to trying other keys.
- **Unknown format_version = reject entirely.** Even if the signature is valid.
- **Base64url signatures must decode to exactly 64 bytes.** Reject anything else.
- **Cache is untrusted storage.** Always re-verify after loading from disk. If verification fails, delete the cache and fetch fresh.

## Concurrency Model

- `AirStrings` exposes state via `StateFlow` — thread-safe, observable from any thread/coroutine.
- Internal `CoroutineScope` uses `SupervisorJob() + Dispatchers.Main` for state emissions. Network and disk I/O dispatched to `Dispatchers.IO`.
- All public `StateFlow` properties are read-only (`StateFlow<T>`). Only `AirStrings` holds the backing `MutableStateFlow`.
- `BundleFetcher` uses OkHttp's synchronous API called from `Dispatchers.IO` — simpler than async callbacks, coroutine cancellation works naturally.
- `BundleVerifier` and `BundleStore` are stateless or thread-safe. No synchronization needed.
- Foreground refresh uses `ProcessLifecycleOwner` with `DefaultLifecycleObserver.onStart()` to detect app entering foreground.
- The background refresh launched in `init` uses `SupervisorJob` so failures don't cancel the scope.
- `onStringsUpdated` callback is always invoked on the main thread (`Dispatchers.Main`).
- `close()` cancels the internal `CoroutineScope` and removes the lifecycle observer. After `close()`, all `StateFlow` values remain readable but stop updating.

## Lifecycle Management

`AirStrings` implements `Closeable`. The integrator must call `close()` when the SDK is no longer needed (e.g., in `Application.onTerminate()` or when a scoped component is destroyed). Failing to close leaks the coroutine scope and lifecycle observer.

```kotlin
// Typical: application-scoped, closed when process ends (Android manages this)
val airStrings = AirStrings.create(context, configuration)

// Scoped: explicitly closed
override fun onDestroy() {
    airStrings.close()
}
```

For most apps, `AirStrings` is application-scoped and lives for the process lifetime — `close()` is not needed. It exists for testability and scoped use cases.

## Dependency Choices

### OkHttp (`com.squareup.okhttp3:okhttp`)

Sole HTTP dependency. Key constraints:

- **Synchronous calls from coroutines.** Use `OkHttpClient.newCall(request).execute()` wrapped in `withContext(Dispatchers.IO)`. Simpler than enqueue + callback, and respects coroutine cancellation via `Call.cancel()`.
- **ETag support is manual.** OkHttp doesn't auto-send `If-None-Match`. We store ETags alongside cached bundles and add the header ourselves.
- **304 returns a `Response` with code 304.** Check `response.code` before reading body. A 304 has no body.
- **Shared client instance.** A single `OkHttpClient` for the SDK lifetime. The integrator's app may have its own client — that's fine, OkHttp shares connection pools.
- **No interceptors for v1.** Keep the request pipeline transparent.

### BouncyCastle (`org.bouncycastle:bcprov-jdk18on`)

Ed25519 verification. Key constraints:

- **Lightweight API only.** Use `Ed25519PublicKeyParameters` and `Ed25519Signer` directly. Do not register BouncyCastle as a JCA provider — avoids global side effects in the host app.
- **Verify-only.** The SDK never signs. Import only what's needed for verification.
- **Isolated to `BundleVerifier.kt`.** If we need to swap the crypto library later (e.g., to `java.security` EdDSA on API 33+), only one file changes.
- **R8/ProGuard rules** strip unused BouncyCastle classes. Consumer ProGuard rules shipped with the AAR ensure correct stripping.

### kotlinx-coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-android`)

Concurrency and reactive state. Used for:

- `StateFlow` / `MutableStateFlow` — observable state
- `CoroutineScope` — structured concurrency for background refresh
- `withContext(Dispatchers.IO)` — offload network/disk to IO pool
- `SupervisorJob` — isolate failures

### org.json (Android built-in)

JSON parsing via `JSONObject`. No external serialization library.

- **Do not use `JSONObject` for canonical JSON.** `JSONObject` does not guarantee key ordering. The hand-rolled serializer in `CanonicalJson.kt` exists for a reason.
- **Use `JSONObject` only for parsing** incoming bundles and cache metadata.

### Jetpack Compose (`androidx.compose.runtime`) — compileOnly

Optional Compose integration. Added as `compileOnly` — not bundled in the AAR. Apps using Compose already have the runtime. Apps not using Compose are unaffected.

## Testing Standards

### What to test

- **CanonicalJson:** Byte-exact output against the contract example in `docs/contracts/bundle-format.md`. Key sorting, no whitespace, integer format, string escaping, control character escaping, empty strings object.
- **BundleVerifier:** Valid signature passes. Wrong key fails. Unknown key_id fails. Unsupported format_version fails. Invalid base64url fails. Tampered strings fail. Test with real BouncyCastle keypairs — no mocking crypto.
- **BundleStore:** Save/load round-trip. Nil etag. Per-locale isolation. Overwrite. Delete. Corrupted metadata degrades gracefully (returns data with null etag).
- **Base64Url:** Encode/decode round-trip. URL-safe characters. Missing padding. 64-byte signatures produce exactly 86 chars.
- **AirStrings:** Subscript fallback. Subscript with loaded strings. Initial state. Locale resolution. Orchestration: fetch -> verify -> store -> emit (via injected fakes). Anti-downgrade rejection. Cache load on init. Foreground refresh trigger. `close()` cancels scope.

### How to test

- Unit tests use JUnit 5 (`@Test`, `@DisplayName`, assertions via `kotlin.test`).
- `BundleStore` takes an optional `baseDirectory: File` for test isolation — pass a temp directory from `@TempDir`.
- For `BundleVerifier`, generate real Ed25519 keypairs with BouncyCastle's `Ed25519KeyPairGenerator` and sign real canonical JSON. Never mock the crypto.
- **`AirStrings` orchestration** is testable via the `internal` constructor: inject a `BundleFetcher` that returns controlled data, a real `BundleVerifier` with test keys, a `BundleStore` backed by a temp directory, and a `TestScope` from `kotlinx-coroutines-test`. This allows verifying the full fetch -> verify -> store -> emit flow without a real server.
- Tests that need a coroutine scope use `runTest` and `TestScope` from `kotlinx-coroutines-test`. Inject the `TestScope` as the SDK's `CoroutineScope` to control coroutine execution deterministically.
- Build: `./gradlew :airstrings:build`
- Test: `./gradlew :airstrings:test`
- Lint: `./gradlew :airstrings:lint`

## Project Structure

```
airstrings-sdk-android/
├── CLAUDE.md                              # This file (always in context)
├── build.gradle.kts                       # Root build config (plugins, repositories)
├── settings.gradle.kts                    # Module declaration, version catalog
├── gradle.properties                      # JVM args, Android settings
├── gradle/
│   └── libs.versions.toml                 # Version catalog (single source for all versions)
├── airstrings/                            # Library module (AAR)
│   ├── build.gradle.kts                   # Library build config
│   ├── consumer-rules.pro                 # ProGuard rules shipped to consumers
│   ├── proguard-rules.pro                 # SDK's own ProGuard rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml        # android.permission.INTERNET
│       │   └── kotlin/com/airstrings/sdk/ # Source (see Architecture above)
│       └── test/
│           └── kotlin/com/airstrings/sdk/ # Unit tests (JVM, no Android framework)
```

Single module for v1. Multi-module (e.g., separate `airstrings-compose`) when there's a reason.

### Build Configuration

- **Kotlin 2.0+** with explicit API mode (`explicitApi()`) — forces visibility modifiers on all declarations.
- **Android Library** plugin — produces AAR.
- **Version catalog** (`libs.versions.toml`) — single source of truth for all dependency versions.
- **R8/ProGuard consumer rules** — shipped with the AAR so integrators don't need to configure anything.
- **No Kotlin serialization plugin** — `org.json` is sufficient for v1.

## Patterns to Follow

- **Public API surface is minimal.** Only `AirStrings`, `AirStringsConfiguration`, `AirStringsLocale`, `AirStringsError`, and `LocalAirStrings` (Compose) are public. Everything else is `internal`.
- **Objects for stateless utilities.** `CanonicalJson`, `Base64Url` are Kotlin `internal object` declarations.
- **Data classes for immutable models.** `StringBundle` is a data class. `AirStringsConfiguration` is a regular class (not data class) because it contains `ByteArray` — `data class` would generate broken `equals()`/`hashCode()` based on array reference identity. Custom content-based `equals()`/`hashCode()` are implemented manually.
- **Sealed classes for errors.** `AirStringsError` uses sealed class hierarchy for exhaustive `when` matching.
- **Class for stateful services.** `BundleFetcher`, `BundleStore`, `AirStrings` are classes with managed lifecycle. `AirStrings` implements `Closeable`.
- **Constructor injection without interfaces.** `AirStrings` has an `internal` constructor that accepts `BundleFetcher`, `BundleVerifier`, `BundleStore`, and `CoroutineScope`. The public `create()` factory wires production dependencies. Tests use the internal constructor to inject fakes/stubs for each layer. No interfaces needed — concrete types are injected directly.
- **Defensive copy of `ByteArray` keys.** `AirStringsConfiguration` copies all `publicKeys` byte arrays on construction. The caller cannot mutate key material after creating a config. `equals()`/`hashCode()` compare by content, not reference.
- **`applicationContext` always.** `AirStrings.create(context, ...)` immediately calls `context.applicationContext`. The SDK never holds a reference to an Activity, Fragment, or View context.
- **Logging via `android.util.Log`.** Tag: `AirStrings`. One tag for the SDK, category in the message. Respect the principle: log key_id, revision, locale — never raw key material or signature data.
- **`operator fun get(key: String)`** on `AirStrings` for subscript-like access: `strings["key"]` returns the localized string or the key itself as fallback.

## Patterns to Avoid

- **Don't add RxJava or LiveData.** StateFlow is sufficient for coroutines and Compose.
- **Don't add interface abstractions preemptively.** No `BundleFetchable`, `BundleVerifiable`, etc. Constructor injection of concrete types is sufficient for testability.
- **Don't add kotlinx.serialization, Moshi, or Gson.** `org.json.JSONObject` is built into Android and sufficient for one data model.
- **Don't use `JSONObject` for canonical JSON.** `JSONObject` does not guarantee key ordering or compact formatting. The hand-rolled serializer in `CanonicalJson.kt` exists for a reason.
- **Don't register BouncyCastle as a JCA provider.** Use the lightweight API directly. Provider registration has global side effects.
- **Don't add Compose UI components.** The SDK exposes data, not UI. Views/composables are the app's responsibility.
- **Don't retry on signature failure.** If verification fails, the bundle is rejected. Retrying the same CDN edge will return the same bytes.
- **Don't log secrets, keys, or signature bytes.** Log key_id (identifier), revision, locale — never raw key material or signature data.
- **Don't use `GlobalScope`.** Structured concurrency only. The SDK's `CoroutineScope` is tied to its lifecycle.
- **Don't block the main thread.** All I/O (network, disk) runs on `Dispatchers.IO`. State updates emit on `Dispatchers.Main`.
- **Don't cache in memory beyond the strings map.** The `StateFlow<Map<String, String>>` is the single source of truth.

## Public API Surface

```kotlin
// Main entry point
class AirStrings : Closeable {

    // --- Public (read-only state) ---
    val strings: StateFlow<Map<String, String>>
    val currentLocale: StateFlow<String>
    val isReady: StateFlow<Boolean>
    val revision: StateFlow<Int>

    operator fun get(key: String): String  // strings.value[key] ?: key
    suspend fun setLocale(bcp47: String)
    suspend fun refresh()
    fun close()  // cancels scope, removes lifecycle observer

    var onStringsUpdated: ((locale: String, revision: Int) -> Unit)?  // always called on main thread

    // --- Internal (for tests) ---
    internal constructor(
        fetcher: BundleFetcher,
        verifier: BundleVerifier,
        store: BundleStore,
        scope: CoroutineScope,
        configuration: AirStringsConfiguration,
    )

    companion object {
        fun create(context: Context, configuration: AirStringsConfiguration): AirStrings
    }
}

// Configuration — defensively copies all ByteArray values
class AirStringsConfiguration(
    val projectId: String,
    publicKeys: Map<String, ByteArray>,  // copied on construction
    val locale: AirStringsLocale = AirStringsLocale.System,
    val baseUrl: String = "https://cdn.airstrings.com",
) {
    val publicKeys: Map<String, ByteArray>  // deep copy, each ByteArray cloned
}

// Locale
sealed class AirStringsLocale {
    data object System : AirStringsLocale()
    data class Fixed(val bcp47: String) : AirStringsLocale()
}

// Errors — each subclass provides a meaningful message
sealed class AirStringsError(override val message: String) : Exception(message) {
    class UnknownKeyId(val keyId: String) : AirStringsError("Unknown key_id: $keyId")
    class SignatureVerificationFailed : AirStringsError("Ed25519 signature verification failed")
    class UnsupportedFormatVersion(val version: Int) : AirStringsError("Unsupported format_version: $version")
    class BundleDecodingFailed(val reason: String) : AirStringsError("Bundle decoding failed: $reason")
    class InvalidSignatureEncoding : AirStringsError("Signature is not valid base64url or not 64 bytes")
}

// Compose integration
val LocalAirStrings: ProvidableCompositionLocal<AirStrings>
```

## Compose Integration

```kotlin
// Provide at the top of the composition (typically in Application or root Activity)
val airStrings = AirStrings.create(context, configuration)

CompositionLocalProvider(LocalAirStrings provides airStrings) {
    MyApp()
}

// Consume in any composable — reactive to string updates
@Composable
fun WelcomeScreen() {
    val airStrings = LocalAirStrings.current
    val strings by airStrings.strings.collectAsStateWithLifecycle()
    Text(strings["onboarding.title"] ?: "onboarding.title")
}
```

**Important:** `operator fun get()` reads `strings.value` synchronously — it does NOT trigger recomposition. For Compose, always collect the `StateFlow` via `collectAsStateWithLifecycle()` (from `androidx.lifecycle:lifecycle-runtime-compose`) to get automatic recomposition when strings update. The `operator fun get()` is for non-Compose code (ViewModels, Services, one-shot reads).

## v1 Scope Boundaries

**In v1:** Fetch, verify, cache, serve strings. One locale active at a time. Foreground refresh. ETag-based conditional requests. Key rotation via multiple configured public keys.

**Not in v1 (do not build):** Analytics/telemetry, ICU MessageFormat, plural handling, WorkManager background sync, push-triggered updates, multiple simultaneous locales, RxJava/LiveData adapters, Compose preview helpers, server-driven locale negotiation, Hilt/Dagger modules.

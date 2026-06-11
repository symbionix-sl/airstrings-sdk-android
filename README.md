# AirStrings Android SDK

Ed25519-signed localized string bundles for Android. Fetches, verifies, caches, and serves remote strings with Jetpack Compose integration.

**API 26+** · **Kotlin 2.0+** · **Compose-ready**

## Installation

### JitPack

Add JitPack to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the dependency:

```kotlin
dependencies {
    implementation("com.github.symbionix-sl:airstrings-sdk-android:0.4.0")
}
```

### Maven Central — _planned_

## Quick Start

### 1. Configure

```kotlin
val config = AirStringsConfiguration(
    organizationId = "org_your_org_id",
    projectId = "proj_your_project_id",
    environmentId = "env_production",
    publicKeys = listOf("BASE64_ENCODED_ED25519_PUBLIC_KEY"),
)

val airStrings = AirStrings.create(context, config)
```

### 2. Use in Compose

```kotlin
// Provide at the root
CompositionLocalProvider(LocalAirStrings provides airStrings) {
    MyApp()
}

// Consume anywhere
@Composable
fun WelcomeScreen() {
    val airStrings = LocalAirStrings.current
    val strings by airStrings.strings.collectAsStateWithLifecycle()

    Text(strings["onboarding.title"] ?: "")
}
```

### 3. Use outside Compose

```kotlin
// Synchronous read — returns key name as fallback
val title = airStrings["onboarding.title"]
```

## ICU MessageFormat

Strings with `"icu"` format support plurals, select, and interpolation:

```kotlin
// Pattern: "{count, plural, one {# item} other {# items}}"
val text = airStrings.format("items.count", mapOf("count" to 5))
// → "5 items"
```

`"text"` format strings return the value as-is, ignoring arguments.

## Locale Switching

```kotlin
// Switch locale — loads cache instantly, fetches fresh in background
airStrings.setLocale("fr-FR")

// Or configure a fixed locale at init
val config = AirStringsConfiguration(
    // ...
    locale = AirStringsLocale.Fixed("fr-FR"),
)
```

Previous strings stay visible until the new locale loads. No flash of raw keys.

## Reactive State

All state is exposed via `StateFlow`:

| Property | Type | Description |
|----------|------|-------------|
| `strings` | `StateFlow<Map<String, String>>` | All localized strings |
| `currentLocale` | `StateFlow<String>` | Active BCP 47 locale |
| `isReady` | `StateFlow<Boolean>` | `true` after first bundle loads |
| `revision` | `StateFlow<Int>` | Bundle revision number |

```kotlin
// Observe updates
airStrings.onStringsUpdated = { locale, revision ->
    Log.d("AirStrings", "Updated: $locale rev $revision")
}
```

## Security

Every bundle is Ed25519-signed and verified before use:

- Bundles without a valid signature are **rejected** — no exceptions
- Cached bundles are **re-verified** on every load from disk
- **Anti-downgrade** protection prevents replacing newer bundles with older ones
- Public keys are provided at init — never hardcoded or logged
- Signature verification uses [BouncyCastle](https://www.bouncycastle.org/) (isolated to a single file)

## Caching

- Bundles cached to disk per-locale in `context.cacheDir`
- ETag-based conditional requests minimize bandwidth (304 Not Modified)
- Automatic refresh when app enters foreground
- Corrupted cache is detected via re-verification and silently replaced

## Bundled Fallback (offline-safe builds)

Ship published, signed bundles inside your APK so a cold start with no network serves real strings instead of key names. The SDK seeds from `assets/airstrings/bundles/` automatically — no configuration required. Defined by the bundled fallback contract (`docs/contracts/bundled-fallback.md` in the AirStrings platform repo).

### 1. Pull and commit the seed directory

```bash
airstrings bundles pull
git add airstrings/bundles
git commit -m "chore: update bundled fallback strings"
```

This writes the published, signed bundles for the active environment into `airstrings/bundles/`. Run the pull in CI or as a pre-release step to keep the committed seed directory fresh.

### 2. Package the seed directory into assets

Either copy the committed folder into your app module:

```
app/src/main/assets/airstrings/bundles/
```

or map it without copying, by pointing an extra asset root at the folder that *contains* the committed `airstrings/` directory (asset paths are packaged relative to the mapped root, and the SDK reads `assets/airstrings/bundles/`):

```kotlin
android {
    sourceSets {
        getByName("main") {
            assets.srcDirs("../localization")
        }
    }
}
```

with the seed directory committed at `localization/airstrings/bundles/`.

### How seeding behaves

- On startup and on `setLocale`, the cached bundle and the seed bundle compete as candidates: each is fully Ed25519-verified, and the highest revision wins (ties go to the cache)
- A winning seed is persisted to the local cache, so later cold starts work even offline
- A seed never downgrades a newer cached or fetched bundle
- A tampered, wrong-project, or wrong-locale seed is rejected with an error and never served or cached
- A missing seed directory is silently ignored

### Configuration

```kotlin
val config = AirStringsConfiguration(
    // ...
    seedEnabled = true,                      // default; set false to disable seeding
    seedDirectory = "airstrings/bundles",    // default asset path, overridable
)
```

## Error Handling

The SDK never crashes and never throws. All errors degrade gracefully:

| Scenario | Behavior |
|----------|----------|
| No network + no cache | `isReady = false`, key names returned as fallback |
| Network error | Keeps serving cached strings |
| Signature verification fails | Bundle rejected, cache deleted, previous strings kept |
| Unknown key or format version | Bundle rejected |
| Tampered or mismatched seed | Seed rejected with error, never served, never cached |
| Missing seed directory or asset | Silent no-op, identical to pre-seed behavior |

## Cleanup

`AirStrings` implements `Closeable`. For application-scoped usage, cleanup is automatic. For scoped usage:

```kotlin
airStrings.close() // Cancels coroutines, removes lifecycle observer
```

## Requirements

- Android API 26+
- Kotlin 2.0+
- `android.permission.INTERNET` (declared by the SDK)

## License

MIT — see [LICENSE](LICENSE) for details.

package com.airstrings.sdk

import android.content.Context
import android.icu.text.MessageFormat
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.airstrings.sdk.models.StringBundle
import com.airstrings.sdk.models.StringEntry
import com.airstrings.sdk.models.StringFormat
import com.airstrings.sdk.networking.BundleFetcher
import com.airstrings.sdk.networking.FetchResult
import com.airstrings.sdk.security.BundleVerifier
import com.airstrings.sdk.storage.AssetSeedSource
import com.airstrings.sdk.storage.BundleStore
import com.airstrings.sdk.storage.SeedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Main entry point for the AirStrings SDK.
 *
 * A [Closeable] class that fetches, verifies, caches, and exposes remotely-managed
 * localized strings via [StateFlow]. Inject into Jetpack Compose via [LocalAirStrings][com.airstrings.sdk.compose.LocalAirStrings].
 *
 * String access via [get] returns the key name as fallback when no bundle is loaded:
 * ```kotlin
 * val title = airStrings["onboarding.welcome_title"]
 * ```
 */
public class AirStrings : Closeable {

    // MARK: - Public (read-only state)

    public val strings: StateFlow<Map<String, String>> get() = _strings.asStateFlow()
    public val currentLocale: StateFlow<String> get() = _currentLocale.asStateFlow()
    public val isReady: StateFlow<Boolean> get() = _isReady.asStateFlow()
    public val revision: StateFlow<Int> get() = _revision.asStateFlow()

    /** Called when strings update mid-session. Receives locale and new revision. Always called on main thread. */
    public var onStringsUpdated: ((locale: String, revision: Int) -> Unit)? = null

    // MARK: - Private mutable state

    private val _strings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _currentLocale: MutableStateFlow<String>
    private val _isReady = MutableStateFlow(false)
    private val _revision = MutableStateFlow(0)

    /** Full string entries including format metadata, for ICU formatting. */
    private val _entries = MutableStateFlow<Map<String, StringEntry>>(emptyMap())

    // MARK: - Internal machinery

    private val configuration: AirStringsConfiguration
    private lateinit var fetcher: BundleFetcher
    private val httpClient: OkHttpClient
    private val verifier: BundleVerifier
    private val store: BundleStore
    private val seedSource: SeedSource?
    private val scope: CoroutineScope
    private val cachedETags = mutableMapOf<String, String>()
    private val cachedRevisions = mutableMapOf<String, Int>()
    private var activeRefreshJob: kotlinx.coroutines.Job? = null
    private var lifecycleObserver: DefaultLifecycleObserver? = null

    /**
     * Returns the localized string for the given key, or the key itself as fallback.
     *
     * Reads [strings] synchronously — does NOT trigger Compose recomposition.
     * For Compose, collect the [strings] StateFlow via `collectAsStateWithLifecycle()`.
     */
    public operator fun get(key: String): String {
        return _strings.value[key] ?: key
    }

    /**
     * Formats a localized string with the given arguments.
     *
     * - For `"text"` format strings: returns the value as-is, ignoring [args].
     * - For `"icu"` format strings: formats using [android.icu.text.MessageFormat] with the provided [args].
     * - If the key is not found: returns the key itself as fallback.
     * - If ICU formatting fails: returns the raw pattern string (never crashes).
     */
    public fun format(key: String, args: Map<String, Any>): String {
        val entry = _entries.value[key] ?: return key

        return when (entry.format) {
            StringFormat.TEXT -> entry.value
            StringFormat.ICU -> {
                try {
                    val locale = Locale.forLanguageTag(_currentLocale.value)
                    val formatter = MessageFormat(entry.value, locale)
                    formatter.format(args)
                } catch (_: Exception) {
                    entry.value
                }
            }
        }
    }

    /**
     * Switches to a new locale. Loads cached bundle instantly if available,
     * then fetches the latest from CDN in the background.
     *
     * When no cached bundle exists for the new locale, keeps previous strings
     * visible until the network fetch completes. Stale translations are better
     * than flashing raw keys.
     */
    public suspend fun setLocale(bcp47: String) {
        _currentLocale.value = bcp47

        withContext(Dispatchers.IO) {
            applyLocalCandidate(bcp47)
        }
        // No local candidate for new locale: keep previous strings visible
        // until the network fetch completes. Stale translations are better
        // than flashing raw keys.

        // Bypass refresh coalescing — if a refresh for the previous locale is
        // in flight, awaiting it via refresh() would return without ever
        // fetching the new locale. Call performRefresh() directly to guarantee
        // the new locale is fetched.
        performRefresh()
    }

    /**
     * Fetches the latest bundle from CDN for the current locale.
     * Uses ETag for conditional requests. Silent on network errors.
     * Coalesces concurrent calls — if a refresh is already in flight, callers await the existing one.
     */
    public suspend fun refresh() {
        val existing = activeRefreshJob
        if (existing != null && existing.isActive) {
            existing.join()
            return
        }

        val job = scope.launch {
            performRefresh()
        }
        activeRefreshJob = job
        job.join()
        activeRefreshJob = null
    }

    private suspend fun performRefresh() {
        val locale = _currentLocale.value

        // Defense-in-depth: any call site that fires before bootstrap finishes is a no-op,
        // rather than throwing UninitializedPropertyAccessException into the broad catch.
        if (!::fetcher.isInitialized) {
            Log.i(TAG, "Skipping refresh: fetcher not initialized yet (bootstrap in flight)")
            return
        }

        try {
            val etag = cachedETags[locale]?.ifEmpty { null }

            val result = withContext(Dispatchers.IO) {
                fetcher.fetch(
                    organizationId = configuration.organizationId,
                    projectId = configuration.projectId,
                    environmentId = configuration.environmentId,
                    locale = locale,
                    ifNoneMatch = etag,
                )
            }

            when (result) {
                is FetchResult.NotModified -> {
                    Log.i(TAG, "Bundle up to date: $locale")
                    if (!_isReady.value) _isReady.value = true
                }

                is FetchResult.Success -> {
                    val bundle = decodeBundle(result.data) ?: return

                    try {
                        verifier.verify(bundle)
                    } catch (e: AirStringsError) {
                        Log.e(TAG, "Signature verification failed: ${e.message}")
                        return
                    }

                    // Anti-downgrade: don't replace newer revision with older for same locale
                    val knownRevision = cachedRevisions[bundle.locale] ?: 0
                    if (bundle.revision < knownRevision) {
                        Log.w(TAG, "Ignoring stale bundle: rev ${bundle.revision} < current $knownRevision for ${bundle.locale}")
                        return
                    }

                    withContext(Dispatchers.IO) {
                        store.save(result.data, configuration.projectId, configuration.environmentId, locale, result.etag)
                    }
                    cachedETags[locale] = result.etag ?: ""

                    // Only apply if locale hasn't changed during fetch
                    if (locale == _currentLocale.value) {
                        applyBundleInternal(bundle)
                        _isReady.value = true

                        withContext(Dispatchers.Main) {
                            onStringsUpdated?.invoke(locale, bundle.revision)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed for $locale: ${e.message}")
            if (!_isReady.value) {
                // If we have a cached bundle, mark as ready
                val hasCached = withContext(Dispatchers.IO) {
                    store.load(configuration.projectId, configuration.environmentId, locale) != null
                }
                if (hasCached) {
                    _isReady.value = true
                }
                // else: no cache + no network -> isReady stays false, keys return as fallback
            }
        }
    }

    /**
     * Cancels the internal coroutine scope and removes the lifecycle observer.
     * After close(), all StateFlow values remain readable but stop updating.
     */
    override fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        lifecycleObserver?.let { observer ->
            try {
                ProcessLifecycleOwner.get().lifecycle.removeObserver(observer)
            } catch (_: Exception) {
                // May not be on main thread
            }
        }
        lifecycleObserver = null
    }

    // MARK: - Internal constructor (for tests)

    internal constructor(
        fetcher: BundleFetcher,
        verifier: BundleVerifier,
        store: BundleStore,
        scope: CoroutineScope,
        configuration: AirStringsConfiguration,
        seedSource: SeedSource? = null,
    ) {
        this.configuration = configuration
        this.fetcher = fetcher
        this.httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        this.verifier = verifier
        this.store = store
        this.seedSource = seedSource
        this.scope = scope
        this._currentLocale = MutableStateFlow(configuration.locale.resolved())
    }

    private constructor(
        httpClient: OkHttpClient,
        verifier: BundleVerifier,
        store: BundleStore,
        scope: CoroutineScope,
        configuration: AirStringsConfiguration,
        seedSource: SeedSource?,
    ) {
        this.configuration = configuration
        this.httpClient = httpClient
        this.verifier = verifier
        this.store = store
        this.seedSource = seedSource
        this.scope = scope
        this._currentLocale = MutableStateFlow(configuration.locale.resolved())
    }

    /** Sets string entries directly. For testing only. */
    internal fun applyBundle(entries: Map<String, StringEntry>, revision: Int) {
        _strings.value = entries.mapValues { it.value.value }
        _entries.value = entries
        _revision.value = revision
        _isReady.value = true
    }

    // MARK: - Public factory

    public companion object {
        private const val TAG = "AirStrings"
        private const val DEFAULT_CDN_URL = "https://cdn.airstrings.com"

        /**
         * Creates a new AirStrings instance and immediately loads cached strings + fetches fresh ones.
         *
         * Calls the bootstrap endpoint to discover the CDN URL before fetching bundles.
         * Falls back to the default CDN URL if bootstrap fails.
         *
         * Always uses [Context.getApplicationContext] — the SDK never holds a reference
         * to an Activity, Fragment, or View context.
         */
        public fun create(context: Context, configuration: AirStringsConfiguration): AirStrings {
            val appContext = context.applicationContext
            val cacheDir = File(appContext.cacheDir, "airstrings")

            val httpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val seedSource = if (configuration.seedEnabled) {
                AssetSeedSource(assets = appContext.assets, directory = configuration.seedDirectory)
            } else {
                null
            }

            val instance = AirStrings(
                httpClient = httpClient,
                verifier = BundleVerifier(publicKeys = configuration.publicKeys),
                store = BundleStore(baseDirectory = cacheDir),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                configuration = configuration,
                seedSource = seedSource,
            )

            instance.loadLocalBundle()

            instance.scope.launch {
                val cdnBaseUrl = withContext(Dispatchers.IO) {
                    instance.bootstrap(configuration.apiBaseURL)
                }
                instance.fetcher = BundleFetcher(baseUrl = cdnBaseUrl, client = httpClient)
                instance.refresh()
                // Register foreground observer only after fetcher is initialized.
                // ProcessLifecycleOwner is already STARTED at app create-time, so
                // addObserver() delivers onStart synchronously to a DefaultLifecycleObserver.
                // Doing this before fetcher is set raced into performRefresh() and threw
                // UninitializedPropertyAccessException, which the broad catch swallowed —
                // leaving _isReady=false and every key rendering as its own fallback on
                // cold installs with no cached bundle.
                instance.observeForeground()
            }

            return instance
        }
    }

    // MARK: - Private

    private fun applyBundleInternal(bundle: StringBundle) {
        _strings.value = bundle.rawStrings
        _entries.value = bundle.strings
        _revision.value = bundle.revision
        cachedRevisions[bundle.locale] = bundle.revision
    }

    internal fun loadLocalBundle() {
        applyLocalCandidate(_currentLocale.value)
    }

    private class LocalCandidate(
        val bundle: StringBundle,
        val data: ByteArray,
        val etag: String?,
    )

    private fun applyLocalCandidate(locale: String) {
        val cached = cachedCandidate(locale)
        val seed = seedCandidate(locale)

        val seedWins = seed != null && (cached == null || seed.bundle.revision > cached.bundle.revision)
        val winner = (if (seedWins) seed else cached) ?: return

        if (seedWins) {
            store.save(winner.data, configuration.projectId, configuration.environmentId, locale, null)
            cachedETags.remove(locale)
        } else if (winner.etag != null) {
            cachedETags[locale] = winner.etag
        } else {
            cachedETags.remove(locale)
        }

        applyBundleInternal(winner.bundle)
        _isReady.value = true
    }

    private fun cachedCandidate(locale: String): LocalCandidate? {
        val cached = store.load(configuration.projectId, configuration.environmentId, locale) ?: return null

        val bundle = decodeBundle(cached.data)
        if (bundle == null) {
            store.delete(configuration.projectId, configuration.environmentId, locale)
            return null
        }

        return try {
            verifier.verify(bundle)
            LocalCandidate(bundle = bundle, data = cached.data, etag = cached.etag)
        } catch (e: AirStringsError) {
            Log.e(TAG, "Cached bundle verification failed for $locale, clearing cache")
            store.delete(configuration.projectId, configuration.environmentId, locale)
            null
        }
    }

    private fun seedCandidate(locale: String): LocalCandidate? {
        val data = seedSource?.load(locale) ?: return null
        val bundle = decodeBundle(data) ?: return null

        return try {
            verifier.verify(bundle)
            if (bundle.projectId != configuration.projectId) {
                throw AirStringsError.SeedProjectMismatch(expected = configuration.projectId, actual = bundle.projectId)
            }
            if (bundle.locale != locale) {
                throw AirStringsError.SeedLocaleMismatch(expected = locale, actual = bundle.locale)
            }
            LocalCandidate(bundle = bundle, data = data, etag = null)
        } catch (e: AirStringsError) {
            Log.e(TAG, "Seed bundle rejected for $locale: ${e.message}")
            null
        }
    }

    private fun decodeBundle(data: ByteArray): StringBundle? {
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            StringBundle.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Bundle decoding failed: ${e.message}")
            null
        }
    }

    private fun bootstrap(apiBaseURL: String): String {
        return try {
            val request = Request.Builder()
                .url("${apiBaseURL.trimEnd('/')}/v1/sdk/bootstrap")
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: throw IOException("Empty body")
                    val json = JSONObject(body)
                    json.getString("cdn_base_url")
                } else {
                    Log.w(TAG, "Bootstrap returned HTTP ${resp.code}, using default CDN URL")
                    DEFAULT_CDN_URL
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bootstrap failed: ${e.message}, using default CDN URL")
            DEFAULT_CDN_URL
        }
    }

    private fun observeForeground() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { refresh() }
            }
        }
        lifecycleObserver = observer

        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        } catch (e: Exception) {
            Log.w(TAG, "Could not observe process lifecycle: ${e.message}")
        }
    }
}

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
import com.airstrings.sdk.storage.BundleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.Locale

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
    private val fetcher: BundleFetcher
    private val verifier: BundleVerifier
    private val store: BundleStore
    private val scope: CoroutineScope
    private val cachedETags = mutableMapOf<String, String>()
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
     * then fetches the latest from CDN.
     */
    public suspend fun setLocale(bcp47: String) {
        _currentLocale.value = bcp47

        // Try loading cached bundle for new locale
        val cached = withContext(Dispatchers.IO) {
            store.load(configuration.projectId, configuration.environmentId, bcp47)
        }

        if (cached != null) {
            val bundle = decodeBundle(cached.data)
            if (bundle != null) {
                try {
                    verifier.verify(bundle)
                    _strings.value = bundle.rawStrings
                    _entries.value = bundle.strings
                    _revision.value = bundle.revision
                    cachedETags[bcp47] = cached.etag ?: ""
                } catch (e: AirStringsError) {
                    Log.e(TAG, "Cached bundle verification failed for $bcp47, clearing cache")
                    withContext(Dispatchers.IO) {
                        store.delete(configuration.projectId, configuration.environmentId, bcp47)
                    }
                    _strings.value = emptyMap()
                    _entries.value = emptyMap()
                    _revision.value = 0
                }
            }
        } else {
            _strings.value = emptyMap()
            _entries.value = emptyMap()
            _revision.value = 0
        }

        refresh()
    }

    /**
     * Fetches the latest bundle from CDN for the current locale.
     * Uses ETag for conditional requests. Silent on network errors.
     */
    public suspend fun refresh() {
        val locale = _currentLocale.value

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
                    if (bundle.locale == _currentLocale.value && bundle.revision < _revision.value) {
                        Log.w(TAG, "Ignoring stale bundle: rev ${bundle.revision} < current ${_revision.value}")
                        return
                    }

                    withContext(Dispatchers.IO) {
                        store.save(result.data, configuration.projectId, configuration.environmentId, locale, result.etag)
                    }
                    cachedETags[locale] = result.etag ?: ""

                    // Only apply if locale hasn't changed during fetch
                    if (locale == _currentLocale.value) {
                        _strings.value = bundle.rawStrings
                        _entries.value = bundle.strings
                        _revision.value = bundle.revision
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
    ) {
        this.configuration = configuration
        this.fetcher = fetcher
        this.verifier = verifier
        this.store = store
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

        /**
         * Creates a new AirStrings instance and immediately loads cached strings + fetches fresh ones.
         *
         * Always uses [Context.getApplicationContext] — the SDK never holds a reference
         * to an Activity, Fragment, or View context.
         */
        public fun create(context: Context, configuration: AirStringsConfiguration): AirStrings {
            val appContext = context.applicationContext
            val cacheDir = File(appContext.cacheDir, "airstrings")

            val instance = AirStrings(
                fetcher = BundleFetcher(),
                verifier = BundleVerifier(publicKeys = configuration.publicKeys),
                store = BundleStore(baseDirectory = cacheDir),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                configuration = configuration,
            )

            instance.loadCachedBundle()
            instance.observeForeground()

            instance.scope.launch {
                instance.refresh()
            }

            return instance
        }
    }

    // MARK: - Private

    private fun loadCachedBundle() {
        val locale = _currentLocale.value
        val cached = store.load(configuration.projectId, configuration.environmentId, locale) ?: return

        val bundle = decodeBundle(cached.data)
        if (bundle == null) {
            store.delete(configuration.projectId, configuration.environmentId, locale)
            return
        }

        try {
            verifier.verify(bundle)
            _strings.value = bundle.rawStrings
            _entries.value = bundle.strings
            _revision.value = bundle.revision
            _isReady.value = true
            cached.etag?.let { cachedETags[locale] = it }
        } catch (e: AirStringsError) {
            Log.e(TAG, "Cached bundle verification failed, clearing cache")
            store.delete(configuration.projectId, configuration.environmentId, locale)
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

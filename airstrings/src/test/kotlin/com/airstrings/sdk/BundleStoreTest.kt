package com.airstrings.sdk

import com.airstrings.sdk.storage.BundleStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DisplayName("BundleStore")
class BundleStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun createStore(): BundleStore = BundleStore(baseDirectory = tempDir)

    @Test
    @DisplayName("save and load round-trip")
    fun saveAndLoadRoundTrip() {
        val store = createStore()
        val data = """{"format_version":1,"strings":{}}""".toByteArray()
        store.save(data, "proj_test12345678", "en", """"rev:42"""")

        val loaded = store.load("proj_test12345678", "en")
        assertNotNull(loaded)
        assertContentEquals(data, loaded.data)
        assertEquals(""""rev:42"""", loaded.etag)
    }

    @Test
    @DisplayName("load returns null when empty")
    fun loadReturnsNullWhenEmpty() {
        val store = createStore()
        val loaded = store.load("proj_nonexistent", "en")
        assertNull(loaded)
    }

    @Test
    @DisplayName("per-locale isolation")
    fun perLocaleIsolation() {
        val store = createStore()
        val enData = """{"locale":"en"}""".toByteArray()
        val frData = """{"locale":"fr"}""".toByteArray()

        store.save(enData, "proj_test12345678", "en", """"en:1"""")
        store.save(frData, "proj_test12345678", "fr", """"fr:1"""")

        val enLoaded = store.load("proj_test12345678", "en")
        val frLoaded = store.load("proj_test12345678", "fr")

        assertNotNull(enLoaded)
        assertNotNull(frLoaded)
        assertContentEquals(enData, enLoaded.data)
        assertContentEquals(frData, frLoaded.data)
        assertEquals(""""en:1"""", enLoaded.etag)
        assertEquals(""""fr:1"""", frLoaded.etag)
    }

    @Test
    @DisplayName("delete removes cache")
    fun deleteRemovesCache() {
        val store = createStore()
        val data = """{"test":true}""".toByteArray()
        store.save(data, "proj_test12345678", "en", null)

        assertNotNull(store.load("proj_test12345678", "en"))

        store.delete("proj_test12345678", "en")

        assertNull(store.load("proj_test12345678", "en"))
    }

    @Test
    @DisplayName("save with null etag")
    fun saveWithNullEtag() {
        val store = createStore()
        val data = """{"test":true}""".toByteArray()
        store.save(data, "proj_test12345678", "en", null)

        val loaded = store.load("proj_test12345678", "en")
        assertNotNull(loaded)
        assertContentEquals(data, loaded.data)
        assertNull(loaded.etag)
    }

    @Test
    @DisplayName("overwrite existing cache")
    fun overwriteExistingCache() {
        val store = createStore()
        val data1 = """{"revision":1}""".toByteArray()
        val data2 = """{"revision":2}""".toByteArray()

        store.save(data1, "proj_test12345678", "en", """"v1"""")
        store.save(data2, "proj_test12345678", "en", """"v2"""")

        val loaded = store.load("proj_test12345678", "en")
        assertNotNull(loaded)
        assertContentEquals(data2, loaded.data)
        assertEquals(""""v2"""", loaded.etag)
    }

    @Test
    @DisplayName("corrupted metadata still returns data")
    fun corruptedMetadataStillReturnsData() {
        val store = createStore()
        val data = """{"test":true}""".toByteArray()
        store.save(data, "proj_test12345678", "en", """"valid"""")

        // Corrupt the metadata file
        val metadataFile = File(File(File(tempDir, "proj_test12345678"), "en"), "metadata.json")
        metadataFile.writeText("corrupted")

        val loaded = store.load("proj_test12345678", "en")
        assertNotNull(loaded)
        assertContentEquals(data, loaded.data)
        assertNull(loaded.etag)
    }
}

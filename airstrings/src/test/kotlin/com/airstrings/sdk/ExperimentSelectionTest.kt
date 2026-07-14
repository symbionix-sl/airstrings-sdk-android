package com.airstrings.sdk

import com.airstrings.sdk.models.Experiment
import com.airstrings.sdk.models.StringBundle
import com.airstrings.sdk.models.StringEntry
import com.airstrings.sdk.models.StringFormat
import com.airstrings.sdk.security.ExperimentSelection
import org.json.JSONObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DisplayName("ExperimentSelection")
class ExperimentSelectionTest {

    private fun entryWith(
        id: String = "exp_checkout_cta",
        allocation: Map<String, Int> = mapOf("control" to 50, "variant_a" to 50),
        variants: Map<String, String> = mapOf("variant_a" to "V"),
    ): StringEntry = StringEntry(
        value = "base",
        format = StringFormat.TEXT,
        experiment = Experiment(id, allocation, variants),
    )

    @Test
    @DisplayName("bucket reproduces all contract vectors")
    fun bucketVectors() {
        assertEquals(78, ExperimentSelection.bucket("exp_checkout_cta", "user_1"))
        assertEquals(19, ExperimentSelection.bucket("exp_checkout_cta", "user_2"))
        assertEquals(50, ExperimentSelection.bucket("exp_paywall_title", "user_2"))
        assertEquals(78, ExperimentSelection.bucket("exp_paywall_title", "device-9f8e7d"))
        assertEquals(97, ExperimentSelection.bucket("exp_unicode", "ユーザー_1"))
        assertEquals(15, ExperimentSelection.bucket("exp_edge", "u"))
    }

    @Test
    @DisplayName("selection reproduces all contract vectors")
    fun selectionVectors() {
        data class Vector(
            val id: String,
            val assignmentId: String,
            val allocation: Map<String, Int>,
            val expected: ExperimentSelection.Selection,
        )

        val value = "V"
        val vectors = listOf(
            Vector(
                "exp_checkout_cta", "user_1",
                mapOf("control" to 50, "variant_a" to 50),
                ExperimentSelection.Selection.Variant("exp_checkout_cta", "variant_a", value),
            ),
            Vector(
                "exp_checkout_cta", "user_2",
                mapOf("control" to 50, "variant_a" to 50),
                ExperimentSelection.Selection.Control("exp_checkout_cta"),
            ),
            Vector(
                "exp_paywall_title", "user_2",
                mapOf("control" to 34, "variant_a" to 33, "variant_b" to 33),
                ExperimentSelection.Selection.Variant("exp_paywall_title", "variant_a", value),
            ),
            Vector(
                "exp_paywall_title", "device-9f8e7d",
                mapOf("control" to 34, "variant_a" to 33, "variant_b" to 33),
                ExperimentSelection.Selection.Variant("exp_paywall_title", "variant_b", value),
            ),
            Vector(
                "exp_unicode", "ユーザー_1",
                mapOf("control" to 50, "variant_a" to 50),
                ExperimentSelection.Selection.Variant("exp_unicode", "variant_a", value),
            ),
            Vector(
                "exp_edge", "u",
                mapOf("a_variant" to 10, "control" to 90),
                ExperimentSelection.Selection.Control("exp_edge"),
            ),
        )

        for (vector in vectors) {
            val variants = vector.allocation.keys
                .filter { it != "control" }
                .associateWith { value }
            val entry = StringEntry(
                value = "base",
                format = StringFormat.TEXT,
                experiment = Experiment(vector.id, vector.allocation, variants),
            )
            assertEquals(
                vector.expected,
                ExperimentSelection.select(entry, vector.assignmentId),
                "${vector.id}:${vector.assignmentId}",
            )
        }
    }

    @Test
    @DisplayName("allocation not summing to 100 selects base")
    fun invalidSumSelectsBase() {
        val entry = entryWith(allocation = mapOf("control" to 40, "variant_a" to 50))
        assertEquals(ExperimentSelection.Selection.Base, ExperimentSelection.select(entry, "user_1"))
    }

    @Test
    @DisplayName("empty experiment id selects base")
    fun emptyIdSelectsBase() {
        val entry = entryWith(id = "")
        assertEquals(ExperimentSelection.Selection.Base, ExperimentSelection.select(entry, "user_1"))
    }

    @Test
    @DisplayName("missing variant value selects base")
    fun missingVariantValueSelectsBase() {
        val entry = entryWith(variants = emptyMap())
        assertEquals(ExperimentSelection.Selection.Base, ExperimentSelection.select(entry, "user_1"))
    }

    @Test
    @DisplayName("negative allocation weight selects base")
    fun negativeAllocationSelectsBase() {
        val entry = entryWith(allocation = mapOf("control" to -10, "variant_a" to 110))
        assertEquals(ExperimentSelection.Selection.Base, ExperimentSelection.select(entry, "user_1"))
    }

    @Test
    @DisplayName("null assignmentId selects base")
    fun nullAssignmentIdSelectsBase() {
        assertEquals(ExperimentSelection.Selection.Base, ExperimentSelection.select(entryWith(), null))
    }

    @Test
    @DisplayName("no experiment selects base")
    fun noExperimentSelectsBase() {
        val entry = StringEntry("base", StringFormat.TEXT)
        assertEquals(ExperimentSelection.Selection.Base, ExperimentSelection.select(entry, "user_1"))
    }

    @Test
    @DisplayName("control is distinguishable from base")
    fun controlDistinguishableFromBase() {
        val result = ExperimentSelection.select(entryWith(), "user_2")
        assertEquals(ExperimentSelection.Selection.Control("exp_checkout_cta"), result)
    }

    @Test
    @DisplayName("malformed experiment decodes to null without throwing")
    fun malformedExperimentDecodesToNull() {
        val json = JSONObject(
            """
            {
              "format_version": 1,
              "project_id": "proj_test",
              "locale": "en",
              "revision": 1,
              "created_at": "2026-01-01T00:00:00Z",
              "key_id": "key_01",
              "signature": "sig",
              "experiments_signature": "esig",
              "strings": {
                "hello": {
                  "value": "Hello",
                  "format": "text",
                  "experiment": { "id": 123, "allocation": "not-a-map" }
                }
              }
            }
            """.trimIndent(),
        )

        val bundle = StringBundle.fromJson(json)

        assertEquals("Hello", bundle.strings["hello"]?.value)
        assertNull(bundle.strings["hello"]?.experiment)
        assertEquals("esig", bundle.experimentsSignature)
    }

    @Test
    @DisplayName("well-formed experiment decodes")
    fun wellFormedExperimentDecodes() {
        val json = JSONObject(
            """
            {
              "format_version": 1,
              "project_id": "proj_test",
              "locale": "en",
              "revision": 1,
              "created_at": "2026-01-01T00:00:00Z",
              "key_id": "key_01",
              "signature": "sig",
              "strings": {
                "hello": {
                  "value": "Hello",
                  "format": "text",
                  "experiment": {
                    "id": "exp_1",
                    "allocation": { "control": 50, "variant_a": 50 },
                    "variants": { "variant_a": "Hi" }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val bundle = StringBundle.fromJson(json)

        assertNull(bundle.experimentsSignature)
        assertEquals(
            Experiment("exp_1", mapOf("control" to 50, "variant_a" to 50), mapOf("variant_a" to "Hi")),
            bundle.strings["hello"]?.experiment,
        )
    }

    @Test
    @DisplayName("non-string experiments_signature decodes to null")
    fun nonStringExperimentsSignatureDecodesToNull() {
        val json = JSONObject(
            """
            {
              "format_version": 1,
              "project_id": "proj_test",
              "locale": "en",
              "revision": 1,
              "created_at": "2026-01-01T00:00:00Z",
              "key_id": "key_01",
              "signature": "sig",
              "experiments_signature": 123,
              "strings": { "hello": { "value": "Hello", "format": "text" } }
            }
            """.trimIndent(),
        )

        val bundle = StringBundle.fromJson(json)
        assertNull(bundle.experimentsSignature)
    }
}

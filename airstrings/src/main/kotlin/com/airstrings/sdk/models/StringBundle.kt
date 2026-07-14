package com.airstrings.sdk.models

import org.json.JSONException
import org.json.JSONObject

/**
 * An A/B-tested variant set attached to a string key. `allocation` maps variant names
 * (including the reserved `control`) to non-negative integer weights summing to 100;
 * `variants` maps each non-`control` name to its overriding string value.
 */
internal data class Experiment(
    val id: String,
    val allocation: Map<String, Int>,
    val variants: Map<String, String>,
)

/**
 * Represents a single string entry in a bundle, carrying both the value and its format type.
 */
internal data class StringEntry(
    val value: String,
    val format: StringFormat,
    val experiment: Experiment? = null,
)

/**
 * Format type for a string entry: plain text or ICU MessageFormat.
 */
internal enum class StringFormat(internal val rawValue: String) {
    TEXT("text"),
    ICU("icu"),
    ;

    internal companion object {
        fun fromRawValue(raw: String): StringFormat = when (raw) {
            "text" -> TEXT
            "icu" -> ICU
            else -> TEXT
        }
    }
}

internal data class StringBundle(
    val formatVersion: Int,
    val projectId: String,
    val locale: String,
    val revision: Int,
    val createdAt: String,
    val keyId: String,
    val signature: String,
    val strings: Map<String, StringEntry>,
    val experimentsSignature: String? = null,
) {

    /** Raw string values keyed by string key. For backward-compatible access. */
    val rawStrings: Map<String, String>
        get() = strings.mapValues { it.value.value }

    internal companion object {

        fun fromJson(json: JSONObject): StringBundle {
            val stringsObj = json.getJSONObject("strings")
            val strings = mutableMapOf<String, StringEntry>()
            for (key in stringsObj.keys()) {
                val entryObj = stringsObj.getJSONObject(key)
                strings[key] = StringEntry(
                    value = entryObj.getString("value"),
                    format = StringFormat.fromRawValue(entryObj.getString("format")),
                    experiment = parseExperiment(entryObj),
                )
            }

            return StringBundle(
                formatVersion = json.getInt("format_version"),
                projectId = json.getString("project_id"),
                locale = json.getString("locale"),
                revision = json.getInt("revision"),
                createdAt = json.getString("created_at"),
                keyId = json.getString("key_id"),
                signature = json.getString("signature"),
                strings = strings,
                experimentsSignature = json.opt("experiments_signature") as? String,
            )
        }

        private fun parseExperiment(entryObj: JSONObject): Experiment? {
            val experimentObj = entryObj.opt("experiment") as? JSONObject ?: return null
            return try {
                val allocationObj = experimentObj.getJSONObject("allocation")
                val allocation = mutableMapOf<String, Int>()
                for (name in allocationObj.keys()) {
                    allocation[name] = allocationObj.getInt(name)
                }
                val variantsObj = experimentObj.getJSONObject("variants")
                val variants = mutableMapOf<String, String>()
                for (name in variantsObj.keys()) {
                    variants[name] = variantsObj.getString(name)
                }
                Experiment(
                    id = experimentObj.getString("id"),
                    allocation = allocation,
                    variants = variants,
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}

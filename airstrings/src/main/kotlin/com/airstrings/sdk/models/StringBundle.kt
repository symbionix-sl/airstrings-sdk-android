package com.airstrings.sdk.models

import org.json.JSONObject

/**
 * Represents a single string entry in a bundle, carrying both the value and its format type.
 */
internal data class StringEntry(
    val value: String,
    val format: StringFormat,
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
            )
        }
    }
}

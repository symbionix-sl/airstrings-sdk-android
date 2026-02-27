package com.airstrings.sdk.models

import org.json.JSONObject

internal data class StringBundle(
    val formatVersion: Int,
    val projectId: String,
    val locale: String,
    val revision: Int,
    val createdAt: String,
    val keyId: String,
    val signature: String,
    val strings: Map<String, String>,
) {

    internal companion object {

        fun fromJson(json: JSONObject): StringBundle {
            val stringsObj = json.getJSONObject("strings")
            val strings = mutableMapOf<String, String>()
            for (key in stringsObj.keys()) {
                strings[key] = stringsObj.getString(key)
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

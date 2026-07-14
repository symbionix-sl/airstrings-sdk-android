package com.airstrings.sdk.models

/**
 * Produces the canonical JSON byte string used for Ed25519 signature verification.
 *
 * Canonical JSON rules (matching RFC 8785 / JCS subset):
 * - No whitespace between tokens
 * - Object keys sorted lexicographically by Unicode code point (recursive)
 * - No trailing commas
 * - Integers serialized without `.0`
 * - Strings escaped per RFC 8259 (only `"`, `\`, and control chars U+0000-U+001F)
 * - UTF-8 encoding, no BOM
 */
internal object CanonicalJson {

    /**
     * Builds the signed content from a bundle: format_version, project_id, locale,
     * revision, created_at, and strings — field order matches the backend/contract.
     */
    fun signedContent(bundle: StringBundle): ByteArray {
        val json = StringBuilder()
        json.append('{')
        json.append("\"format_version\":").append(bundle.formatVersion)
        json.append(",\"project_id\":").append(escapeString(bundle.projectId))
        json.append(",\"locale\":").append(escapeString(bundle.locale))
        json.append(",\"revision\":").append(bundle.revision)
        json.append(",\"created_at\":").append(escapeString(bundle.createdAt))
        json.append(",\"strings\":{")

        val sortedKeys = bundle.strings.keys.sorted()
        for ((i, key) in sortedKeys.withIndex()) {
            if (i > 0) json.append(',')
            val entry = bundle.strings[key]!!
            json.append(escapeString(key)).append(':')
            json.append("{\"format\":").append(escapeString(entry.format.rawValue))
            json.append(",\"value\":").append(escapeString(entry.value))
            json.append('}')
        }

        json.append("}}")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    // Recipe pinned: top-level order format_version, project_id, locale, revision,
    // created_at, experiments — the experiments block is signed separately from
    // `signedContent`. experiments are keyed by the string key carrying an experiment
    // (sorted); each block: allocation (sorted names -> bare int), id, variants
    // (sorted names -> string). Any change breaks cross-platform signature parity.
    fun experimentsSignedContent(bundle: StringBundle): ByteArray {
        val json = StringBuilder()
        json.append('{')
        json.append("\"format_version\":").append(bundle.formatVersion)
        json.append(",\"project_id\":").append(escapeString(bundle.projectId))
        json.append(",\"locale\":").append(escapeString(bundle.locale))
        json.append(",\"revision\":").append(bundle.revision)
        json.append(",\"created_at\":").append(escapeString(bundle.createdAt))
        json.append(",\"experiments\":{")

        val experimentKeys = bundle.strings.keys
            .filter { bundle.strings[it]?.experiment != null }
            .sorted()
        for ((i, key) in experimentKeys.withIndex()) {
            if (i > 0) json.append(',')
            val experiment = bundle.strings[key]!!.experiment!!
            json.append(escapeString(key)).append(":{")

            json.append("\"allocation\":{")
            for ((j, name) in experiment.allocation.keys.sorted().withIndex()) {
                if (j > 0) json.append(',')
                json.append(escapeString(name)).append(':').append(experiment.allocation.getValue(name))
            }
            json.append('}')

            json.append(",\"id\":").append(escapeString(experiment.id))

            json.append(",\"variants\":{")
            for ((j, name) in experiment.variants.keys.sorted().withIndex()) {
                if (j > 0) json.append(',')
                json.append(escapeString(name)).append(':').append(escapeString(experiment.variants.getValue(name)))
            }
            json.append('}')

            json.append('}')
        }

        json.append("}}")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escapeString(s: String): String {
        val result = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> result.append("\\\"")
                '\\' -> result.append("\\\\")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        result.append(String.format("\\u%04x", ch.code))
                    } else {
                        result.append(ch)
                    }
                }
            }
        }
        result.append('"')
        return result.toString()
    }
}

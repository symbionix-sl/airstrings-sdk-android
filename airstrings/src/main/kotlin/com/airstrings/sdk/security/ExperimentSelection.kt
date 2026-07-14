package com.airstrings.sdk.security

import com.airstrings.sdk.models.StringEntry
import java.security.MessageDigest

/**
 * Resolves which variant of an experiment a given assignment falls into, per the
 * normative bucketing recipe in `docs/contracts/sdk-requirements.md`. Any validity
 * violation resolves to [Selection.Base] (serve the base value, no exposure) while
 * the bundle itself stays accepted.
 */
internal object ExperimentSelection {

    internal sealed interface Selection {
        data object Base : Selection

        data class Control(val experimentId: String) : Selection

        data class Variant(
            val experimentId: String,
            val name: String,
            val value: String,
        ) : Selection
    }

    fun bucket(experimentId: String, assignmentId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$experimentId:$assignmentId".toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 4) {
            value = (value shl 8) or (digest[i].toLong() and 0xFFL)
        }
        return (value % 100L).toInt()
    }

    fun select(entry: StringEntry, assignmentId: String?): Selection {
        val experiment = entry.experiment ?: return Selection.Base
        if (assignmentId == null) return Selection.Base
        if (experiment.id.isEmpty()) return Selection.Base

        val allocation = experiment.allocation
        if (allocation.values.any { it < 0 }) return Selection.Base
        if (allocation.values.sum() != 100) return Selection.Base

        val b = bucket(experiment.id, assignmentId)
        val names = allocation.keys.sortedWith { a, c -> compareCodePoints(a, c) }

        var acc = 0
        for (name in names) {
            acc += allocation.getValue(name)
            if (b < acc) {
                if (name == "control") {
                    return Selection.Control(experiment.id)
                }
                val value = experiment.variants[name] ?: return Selection.Base
                return Selection.Variant(experiment.id, name, value)
            }
        }
        return Selection.Base
    }

    private fun compareCodePoints(a: String, b: String): Int {
        val ai = a.codePoints().iterator()
        val bi = b.codePoints().iterator()
        while (ai.hasNext() && bi.hasNext()) {
            val diff = ai.nextInt().compareTo(bi.nextInt())
            if (diff != 0) return diff
        }
        return when {
            ai.hasNext() -> 1
            bi.hasNext() -> -1
            else -> 0
        }
    }
}

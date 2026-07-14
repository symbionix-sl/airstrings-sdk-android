package com.airstrings.sdk

/**
 * Emitted the first time an experiment variant is served for a key. Forward to your analytics
 * pipeline to attribute outcomes to variants. Delivered asynchronously on the main thread.
 */
public data class ExposureEvent(
    public val key: String,
    public val experimentId: String,
    public val variant: String,
    public val locale: String,
    public val assignmentId: String,
)

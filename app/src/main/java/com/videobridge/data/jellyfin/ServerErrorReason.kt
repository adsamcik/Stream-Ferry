package com.videobridge.data.jellyfin

import com.videobridge.core.redaction.LogRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Best-effort extraction of a human-readable reason from a Jellyfin error response body, so a 4xx/5xx
 * can explain *why* it failed instead of just "HTTP 500". Parses the common Jellyfin/.NET error JSON
 * shapes (ProblemDetails / exception payloads) and otherwise falls back to a short text snippet.
 *
 * The result is ALWAYS run through [LogRedactor] and length-capped, so a server that echoes back a URL
 * or token in its error body can never leak one into the UI or the diagnostics log. This is a pure,
 * framework-free helper kept in the data layer (it needs the JSON parser) and unit-tested.
 */
internal object ServerErrorReason {

    private const val MAX_ERROR_BODY = 16_384
    private const val MAX_REASON = 240

    /** JSON fields Jellyfin/.NET use to carry an error message, in priority order. */
    private val ERROR_FIELDS = listOf(
        "detail", "title", "message", "error", "Message", "ErrorMessage", "ExceptionMessage", "error_description",
    )

    /** Returns a short, redacted reason for an error response, or null when nothing useful is present. */
    fun extract(body: String?, contentType: String?, json: Json): String? {
        val trimmed = (body ?: "").trim()
        if (trimmed.isEmpty()) return null
        val looksJson = contentType?.contains("json", ignoreCase = true) == true || trimmed.startsWith("{")
        // Only parse reasonably-sized bodies as JSON; huge bodies are almost always HTML error pages,
        // and parsing/redacting megabytes on an error path is wasteful.
        val extracted: String = if (looksJson && trimmed.length <= MAX_ERROR_BODY) {
            runCatching {
                val obj = json.parseToJsonElement(trimmed).jsonObject
                ERROR_FIELDS.asSequence()
                    .mapNotNull { obj[it]?.jsonPrimitive?.contentOrNull }
                    .firstOrNull { it.isNotBlank() }
                    // No recognised error field: surface the raw JSON only if it carries anything at all
                    // (an empty "{}" object is noise and collapses to null below).
                    ?: if (obj.isEmpty()) "" else trimmed
            }.getOrDefault(trimmed)
        } else {
            trimmed
        }
        return LogRedactor.redact(extracted.take(MAX_ERROR_BODY))
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_REASON)
            .ifBlank { null }
    }
}

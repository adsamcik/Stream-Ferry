package com.adsamcik.streamferry.data.cast

import java.util.UUID

/** A Cast application session can contain media loaded by several independent senders. */
internal class CastMediaOwnership {
    private var namespace = UUID.randomUUID().toString()
    private var issuedLoads = 0L
    private var expectedUrl: String? = null
    private var expectedToken: String? = null
    private var accepted = false
    private var retiredMedia: Pair<String?, String?>? = null
    var isLost: Boolean = false
        private set

    /** Tokens recognize every app-issued LOAD without retaining URL or nonce histories. */
    fun beginLoad(url: String, previousUrl: String?, previousToken: String?): String {
        check(issuedLoads < Long.MAX_VALUE) { "Cast load identity exhausted" }
        val token = "$namespace:${++issuedLoads}"
        expectedUrl = url
        expectedToken = token
        accepted = false
        retiredMedia = previousUrl to previousToken
        isLost = false
        return token
    }

    /** Old cached media is harmless until this load is observed; an external replacement is terminal. */
    fun observe(url: String?, token: String?, interrupted: Boolean): Boolean {
        if (isLost) return false
        val expected = expectedUrl ?: return true // Connected, before our first load.
        val ordinal = token?.takeIf { it.startsWith("$namespace:") }
            ?.substring(namespace.length + 1)?.toLongOrNull()
        // The shared SDK cache can lag across any number of accepted or pending replacements.
        if (ordinal != null && ordinal in 1 until issuedLoads) return false
        // Some receivers omit optional customData. URL-only matching retains their compatibility;
        // a same-URL replacement with stripped metadata cannot be distinguished on the public SDK.
        val matches = url == expected && (token == null || token == expectedToken)
        if (!accepted && retiredMedia == (url to token) && (!matches || interrupted)) return false
        if (interrupted && (accepted || matches)) {
            isLost = true
            return false
        }
        if (matches) {
            accepted = true
            return true
        }
        if (accepted && (url != null || token != null)) {
            isLost = true
        }
        return false
    }

    fun reset() {
        namespace = UUID.randomUUID().toString()
        issuedLoads = 0L
        expectedUrl = null
        expectedToken = null
        accepted = false
        retiredMedia = null
        isLost = false
    }
}

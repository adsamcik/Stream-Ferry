package com.adsamcik.streamferry.data.proxy

import java.io.Closeable
import java.io.IOException

/** Owns a provider open and its eventual descriptors, including cancellation/open races. */
internal class LocalContentRead(private val cancelOpen: () -> Unit) : Closeable {
    private val lock = Any()
    private var closed = false
    private val resources = ArrayList<Closeable>()

    fun <T : Closeable> register(resource: T): T {
        synchronized(lock) {
            if (!closed) {
                resources += resource
                return resource
            }
        }
        runCatching { resource.close() }
        throw IOException("Local media read was cancelled")
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            resources.reversed().also { resources.clear() }
        }
        runCatching(cancelOpen)
        owned.forEach { runCatching { it.close() } }
    }
}

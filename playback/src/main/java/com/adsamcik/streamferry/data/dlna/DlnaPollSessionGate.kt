package com.adsamcik.streamferry.data.dlna

/** Linearizable ownership gate for results returned by blocking DLNA poll calls. */
internal class DlnaPollSessionGate {
    private val lock = Any()
    private var generation = 0L

    fun begin(): Long = synchronized(lock) { ++generation }

    fun invalidate() {
        synchronized(lock) { ++generation }
    }

    fun isCurrent(token: Long): Boolean = synchronized(lock) { token == generation }

    fun commit(token: Long, action: () -> Unit): Boolean = synchronized(lock) {
        if (token != generation) {
            false
        } else {
            action()
            true
        }
    }
}

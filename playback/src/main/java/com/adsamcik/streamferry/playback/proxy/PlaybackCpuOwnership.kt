package com.adsamcik.streamferry.playback.proxy

/** One service instance owns its playback intent and short-lived source/producer work leases. */
internal class PlaybackCpuOwnership(private val change: (Boolean) -> Unit) : AutoCloseable {
    private val lock = Any()
    private var playing = false
    private var work = 0
    private var held = false
    private var closed = false

    fun setPlaybackActive(active: Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        playing = active
        reconcile()
    }

    fun beginWork(): AutoCloseable = synchronized(lock) {
        if (closed) return@synchronized AutoCloseable {}
        work++
        try { reconcile() } catch (error: Exception) { work--; throw error }
        var finished = false
        AutoCloseable {
            synchronized(lock) {
                if (!finished) {
                    finished = true
                    if (!closed) { work--; reconcile() }
                }
            }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        playing = false
        work = 0
        reconcile()
    }

    private fun reconcile() {
        val required = !closed && (playing || work > 0)
        if (required != held) { change(required); held = required }
    }
}

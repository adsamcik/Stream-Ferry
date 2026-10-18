package com.adsamcik.streamferry.ui.artwork

import coil.key.Keyer
import coil.request.Options
import com.adsamcik.streamferry.source.api.ArtworkRef
import java.util.concurrent.atomic.AtomicLong

/** Account and image-version identities are already encoded in the opaque artwork reference. */
class ArtworkRefKeyer : Keyer<ArtworkRef> {
    private val generation = AtomicLong()

    override fun key(data: ArtworkRef, options: Options): String =
        "artwork:${generation.get()}:${data.source.storageNamespace}:${data.opaqueId.length}:${data.opaqueId}"

    /** An old in-flight request may finish after clearing memory; its old key must remain unusable. */
    fun invalidate() { generation.incrementAndGet() }
}

package com.adsamcik.streamferry.data.audio

/** One embedded stream. Language and display labels are not unique track identities. */
data class LocalAudioTrack(
    val index: Int,
    val extractorTrackId: Int,
    val formatId: String?,
    val language: String?,
    val label: String,
    val isDefault: Boolean,
    val roleFlags: Int,
    val mimeType: String?,
    val codecs: String?,
    val channelCount: Int,
    val sampleRate: Int,
    val unsupportedReason: String? = null,
)

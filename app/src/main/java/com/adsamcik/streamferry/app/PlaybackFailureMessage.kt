package com.adsamcik.streamferry.app

import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.adsamcik.streamferry.domain.PlaybackTargetException
import com.adsamcik.streamferry.permissions.LocalNetworkAccessDeniedException
import com.adsamcik.streamferry.playback.PlaybackAttemptDescriptor
import com.adsamcik.streamferry.playback.PlaybackFailureCause
import com.adsamcik.streamferry.playback.PlaybackFailureStage
import com.adsamcik.streamferry.playback.PlaybackPreparationException
import com.adsamcik.streamferry.source.api.SourceHttpException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.BindException

/** App-authored preflight guidance, never a server/renderer exception message. */
internal class PlaybackRequestException(message: String) : IllegalStateException(message)

internal inline fun checkPlaybackRequest(condition: Boolean, message: () -> String) {
    if (!condition) throw PlaybackRequestException(message())
}

/** Explain known evidence without leaking URLs, file paths, tokens or arbitrary exception text. */
internal fun playbackFailureMessage(error: Throwable?, attempt: PlaybackAttemptDescriptor?): String {
    val chain = generateSequence(error) { it.cause }.take(16).toList()
    chain.filterIsInstance<PlaybackRequestException>().firstOrNull()?.let { return it.message.orEmpty() }
    if (chain.any { it is LocalNetworkAccessDeniedException }) {
        return "Local-network access is off. Allow it in Android app settings so this phone can connect to your TV."
    }
    val preparation = chain.filterIsInstance<PlaybackPreparationException>().firstOrNull()
    when (preparation?.message) {
        "The phone playback service was rejected.", "The phone playback service did not start in time." ->
            return "The phone couldn't start its playback service. Keep Stream Ferry open and try again."
        "No phone LAN address is available for the media gateway." ->
            return "The phone has no local-network address for streaming. Connect it to the TV's network, then retry."
        "Couldn't start the local media gateway.", "Couldn't establish the phone-hosted media gateway." ->
            return "The phone couldn't open its media gateway. Retry, or open Diagnostics if this keeps happening."
    }
    val http = chain.filterIsInstance<SourceHttpException>().firstOrNull()
    if (http != null) return when (http.statusCode) {
        401 -> "The media-source login has expired. Sign in again before retrying."
        403 -> "The media source denied access to this video. Check your account's playback permissions."
        404, 410 -> "The video is no longer available from the media source. Refresh the library or choose another video."
        429 -> "The media source is receiving too many requests. Wait a moment, then retry."
        in 500..599 -> "The media source returned HTTP ${http.statusCode} and couldn't prepare this video. Check the server, then retry."
        else -> "The media source rejected the request (HTTP ${http.statusCode}). Check the source or choose another video."
    }
    if (chain.any { it is FileNotFoundException }) {
        return "The phone can't read this video. Select the file again or download a fresh copy."
    }
    if (chain.any { it is SecurityException }) {
        return "Access to the video was denied. Allow access to the file or media library, then select it again."
    }
    if (chain.any { it is BindException } || attempt?.failureStage == PlaybackFailureStage.PROXY) {
        return "The phone couldn't serve the stream to the TV. Retry, or open Diagnostics to check the local media gateway."
    }
    val target = chain.filterIsInstance<PlaybackTargetException>().firstOrNull()
    val cause = attempt?.failureCause?.takeUnless { it == PlaybackFailureCause.UNKNOWN } ?: when (target?.kind) {
        PlaybackFailureKind.FORMAT -> PlaybackFailureCause.FORMAT_OR_CODEC
        PlaybackFailureKind.NETWORK -> PlaybackFailureCause.TRANSIENT_NETWORK
        else -> null
    }
    return when (cause) {
        PlaybackFailureCause.ENDPOINT_UNAVAILABLE ->
            "The TV connection couldn't be established. Make sure the TV is on and accepts connections, then retry or choose another TV."
        PlaybackFailureCause.TRANSIENT_NETWORK -> if (attempt?.failureStage == PlaybackFailureStage.ENDPOINT_CONNECTION) {
            "The phone couldn't reach the TV. Check both devices' network connections, then retry."
        } else {
            "The TV lost access to the stream. Check the connection between the TV and this phone, then retry."
        }
        PlaybackFailureCause.FORMAT_OR_CODEC ->
            "The TV rejected this video format. Choose a video in a supported format or try another TV."
        PlaybackFailureCause.AUDIO_OR_SUBTITLE ->
            "The selected audio or subtitle track couldn't be used. Reopen this video and choose a different track."
        PlaybackFailureCause.THROUGHPUT ->
            "The connection couldn't keep up with the video. Improve the network connection or lower video quality."
        PlaybackFailureCause.SERVER_TRANSCODE ->
            "The server couldn't convert this video. Check the server's transcoding settings or choose another video."
        PlaybackFailureCause.ON_DEVICE_TRANSCODE ->
            "The phone couldn't convert this video. Start it again with a lower resolution or another output mode."
        PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE ->
            "The media source couldn't provide the video. Check that the source is available, then retry."
        else -> when {
            preparation != null -> "The video couldn't be prepared for streaming. Retry or open Diagnostics to check the source and phone."
            attempt?.failureStage == PlaybackFailureStage.ENDPOINT_CONNECTION ->
                "The TV connection failed without a specific reason. Check the TV, then retry or open Diagnostics."
            chain.any { it is IOException } -> "A connection or media-read operation failed. Check the source and network, then retry."
            else -> "Playback failed without a specific reason from the TV or source. Retry or open Diagnostics for connection details."
        }
    }
}

package com.adsamcik.streamferry.data.cast

import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaError

/** Receiver download failures and sender transport failures do not establish an incompatible format. */
internal object CastFailureClassifier {
    fun mediaError(detailedCode: Int?): PlaybackFailureKind = when (detailedCode) {
        MediaError.DetailedErrorCode.MEDIA_DECODE,
        MediaError.DetailedErrorCode.MEDIA_SRC_NOT_SUPPORTED,
        -> PlaybackFailureKind.FORMAT
        MediaError.DetailedErrorCode.MEDIA_NETWORK,
        MediaError.DetailedErrorCode.MEDIAKEYS_NETWORK,
        MediaError.DetailedErrorCode.NETWORK_UNKNOWN,
        MediaError.DetailedErrorCode.SEGMENT_NETWORK,
        MediaError.DetailedErrorCode.HLS_NETWORK_MASTER_PLAYLIST,
        MediaError.DetailedErrorCode.HLS_NETWORK_PLAYLIST,
        MediaError.DetailedErrorCode.HLS_NETWORK_NO_KEY_RESPONSE,
        MediaError.DetailedErrorCode.HLS_NETWORK_KEY_LOAD,
        MediaError.DetailedErrorCode.DASH_NETWORK,
        MediaError.DetailedErrorCode.SMOOTH_NETWORK,
        -> PlaybackFailureKind.NETWORK
        // LOAD_FAILED, IDLE/ERROR, malformed manifests and invalid segments do not prove a codec
        // incompatibility. In particular, codes >= 300 are not all network failures.
        else -> PlaybackFailureKind.UNKNOWN
    }

    fun command(statusCode: Int, detailedCode: Int?): PlaybackFailureKind = when (statusCode) {
        CastStatusCodes.NETWORK_ERROR,
        CastStatusCodes.TIMEOUT,
        CastStatusCodes.DEVICE_CONNECTION_SUSPENDED,
        -> PlaybackFailureKind.NETWORK
        else -> mediaError(detailedCode)
    }
}

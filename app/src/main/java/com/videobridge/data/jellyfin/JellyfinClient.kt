package com.videobridge.data.jellyfin

import com.videobridge.core.resilience.Backoff
import com.videobridge.core.resilience.RetryBudget
import com.videobridge.core.resilience.UpstreamRetry
import com.videobridge.core.segments.MediaSegment
import com.videobridge.core.segments.SegmentType
import com.videobridge.data.jellyfin.HttpJellyfinRepository.Companion.TICKS_PER_SECOND
import com.videobridge.domain.MediaChapter
import com.videobridge.domain.MediaItem
import com.videobridge.domain.MediaTrack
import com.videobridge.domain.UpstreamSource
import com.videobridge.logging.DiagnosticsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Concrete [JellyfinApi] over the documented Jellyfin HTTP API (see [JellyfinApiContract]).
 *
 * Holds the per-session state — base URL, access token, user id — in memory only. The token and the
 * fully-resolved upstream stream URLs are SECRETS: they are kept here / in the proxy session and are
 * never logged or handed to the TV (the proxy substitutes a phone session URL). Library browsing and
 * item details are also served here so the repositories stay thin.
 *
 * All blocking HTTP runs on [Dispatchers.IO]. Responses are parsed leniently
 * (`ignoreUnknownKeys = true`) so server-version field drift doesn't break the app.
 */
class JellyfinClient(
    private val httpClient: OkHttpClient,
    private val deviceId: String,
    private val deviceName: String,
    private val appVersion: String,
    private val logger: DiagnosticsLogger,
    private val clientName: String = "Video Bridge",
) : JellyfinApi {

    @Volatile var baseUrl: String? = null
        private set
    @Volatile private var accessToken: String? = null
    @Volatile var userId: String? = null
        private set

    val isConfigured: Boolean get() = baseUrl != null
    val isAuthenticated: Boolean get() = accessToken != null && userId != null

    /**
     * In-app poster URL for an item, sized for the gallery, or null if the server isn't configured or the
     * item has no Primary image. The URL is **tokenless** — the access token is sent only via the
     * Authorization header (see [imageAuthHeader]) by the image loader, so it never lands in a cache key,
     * a log, or anywhere it could leak. This URL is for THIS phone's UI only and is never given to the TV.
     */
    fun posterUrl(itemId: String, imageTag: String?, targetWidthPx: Int): String? {
        val b = baseUrl?.toHttpUrlOrNull() ?: return null
        return b.newBuilder()
            .addPathSegment("Items").addPathSegment(itemId).addPathSegment("Images").addPathSegment("Primary")
            .addQueryParameter("fillWidth", targetWidthPx.coerceIn(64, 1280).toString())
            .addQueryParameter("quality", "90")
            .apply { imageTag?.let { addQueryParameter("tag", it) } }
            .build()
            .toString()
    }

    /**
     * In-app **chapter** ("section") thumbnail URL for the seek-preview scrubber, or null if the server
     * isn't configured. Like [posterUrl] it is **tokenless** (the access token is sent only via the
     * Authorization header by the image loader) and is shown on THIS phone only — never given to the TV.
     */
    fun chapterImageUrl(itemId: String, chapterIndex: Int, imageTag: String?, targetWidthPx: Int): String? {
        val b = baseUrl?.toHttpUrlOrNull() ?: return null
        return b.newBuilder()
            .addPathSegment("Items").addPathSegment(itemId)
            .addPathSegment("Images").addPathSegment("Chapter").addPathSegment(chapterIndex.toString())
            .addQueryParameter("fillWidth", targetWidthPx.coerceIn(64, 640).toString())
            .addQueryParameter("quality", "80")
            .apply { imageTag?.let { addQueryParameter("tag", it) } }
            .build()
            .toString()
    }

    /** Current MediaBrowser Authorization header for image requests, or null when not logged in. */
    fun imageAuthHeader(): String? = if (accessToken != null) authHeaderValue() else null

    /** Host of the configured Jellyfin server, used to scope the image-auth header. */
    fun serverHost(): String? = baseUrl?.toHttpUrlOrNull()?.host


    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Bounded retry for transient API failures (network blips / 5xx). */
    private val retryBudget = RetryBudget(maxConsecutiveFailures = 3)

    /** Upstream sources resolved during PlaybackInfo, keyed by media source id (secret values). */
    private val upstreamCache = ConcurrentHashMap<String, UpstreamSource>()

    // ----- session management (used by the auth repository) -----

    fun configureServer(normalizedBaseUrl: String) { baseUrl = normalizedBaseUrl }

    fun setAuth(token: String, userId: String) {
        this.accessToken = token
        this.userId = userId
    }

    fun clearAuth() {
        accessToken = null
        userId = null
        upstreamCache.clear()
    }

    fun clearAll() {
        baseUrl = null
        clearAuth()
    }

    // ----- JellyfinApi: identity / auth -----

    override suspend fun systemInfoPublic(): String? = publicInfo()?.name

    data class PublicInfo(val serverId: String?, val name: String)

    /** Public, unauthenticated server info — name + the stable server Id used for anti-spoof pinning. */
    suspend fun publicInfo(): PublicInfo? = withContext(Dispatchers.IO) {
        catchingNonCancel {
            val body = exec(get(path("System", "Info", "Public")))
            val info = json.decodeFromString(PublicSystemInfo.serializer(), body)
            PublicInfo(info.id, listOfNotNull(info.serverName, info.version?.let { "v$it" }).joinToString(" ").ifBlank { "Jellyfin" })
        }.getOrNull()
    }

    override suspend fun authenticateByName(username: String, password: String): JellyfinApi.AuthResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("Username", username)
                put("Pw", password)
            }
            val resp = exec(post(path("Users", "AuthenticateByName"), body))
            val result = json.decodeFromString(AuthenticationResult.serializer(), resp)
            val token = result.accessToken ?: error("No access token in response")
            val uid = result.user?.id ?: error("No user id in response")
            JellyfinApi.AuthResult(accessToken = token, userId = uid, serverVersion = null)
        }

    // ----- Quick Connect (device-code style login; no password typed on the phone) -----

    /** True when the server administrator has enabled the Quick Connect feature. */
    suspend fun quickConnectEnabled(): Boolean = withContext(Dispatchers.IO) {
        catchingNonCancel {
            exec(get(path("QuickConnect", "Enabled"))).trim().equals("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    /**
     * Begin a Quick Connect handshake. Sends the device-identifying Authorization header (no token) so
     * the server can associate the pending request with this device, and returns the user-facing [code]
     * to approve plus the [secret] used to poll. Never log the secret.
     */
    suspend fun quickConnectInitiate(): QuickConnectHandshake = withContext(Dispatchers.IO) {
        val resp = exec(postNoBody(path("QuickConnect", "Initiate")))
        val result = json.decodeFromString(QuickConnectResultDto.serializer(), resp)
        val secret = result.secret ?: error("Quick Connect did not return a secret")
        val code = result.code ?: error("Quick Connect did not return a code")
        QuickConnectHandshake(secret = secret, code = code)
    }

    /** Poll a pending Quick Connect handshake; true once the user has approved it on their server. */
    suspend fun quickConnectPoll(secret: String): Boolean = withContext(Dispatchers.IO) {
        val resp = exec(get(path("QuickConnect", "Connect"), "secret" to secret))
        json.decodeFromString(QuickConnectResultDto.serializer(), resp).authenticated
    }

    /** Exchange an approved Quick Connect secret for an access token + user id. */
    suspend fun authenticateWithQuickConnect(secret: String): JellyfinApi.AuthResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("Secret", secret) }
            val resp = exec(post(path("Users", "AuthenticateWithQuickConnect"), body))
            val result = json.decodeFromString(AuthenticationResult.serializer(), resp)
            val token = result.accessToken ?: error("No access token in response")
            val uid = result.user?.id ?: error("No user id in response")
            JellyfinApi.AuthResult(accessToken = token, userId = uid, serverVersion = null)
        }

    /** Approve a pending Quick Connect [code] using the current (admin) session. Used for testing. */
    suspend fun quickConnectAuthorize(code: String): Boolean = withContext(Dispatchers.IO) {
        catchingNonCancel {
            exec(post(path("QuickConnect", "Authorize"), buildJsonObject {}, "code" to code))
                .trim().equals("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    // ----- library browsing (used by the media library repository) -----

    suspend fun userViews(): List<MediaItem> = withContext(Dispatchers.IO) {
        val uid = userId ?: error("Not authenticated")
        val body = exec(get(path("Users", uid, "Views")))
        json.decodeFromString(ItemsResult.serializer(), body).items.map { it.toMediaItem() }
    }

    data class ItemsPage(val items: List<MediaItem>, val totalRecordCount: Int?)

    /**
     * The user's "Continue Watching" list — items with a saved playback position, most-recently-played
     * first — from `/Users/{userId}/Items/Resume`. Video only; poster art is requested so the row can
     * show thumbnails. Empty (never an error) when nothing is in progress.
     */
    suspend fun resumeItems(limit: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        val uid = userId ?: error("Not authenticated")
        val url = get(
            path("Users", uid, "Items", "Resume"),
            "limit" to limit.toString(),
            "mediaTypes" to "Video",
            "fields" to "Overview",
            "enableImageTypes" to "Primary",
            "imageTypeLimit" to "1",
            "enableTotalRecordCount" to "false",
        )
        json.decodeFromString(ItemsResult.serializer(), exec(url)).items.map { it.toMediaItem() }
    }

    suspend fun itemsPage(parentId: String, startIndex: Int, limit: Int): ItemsPage =
        withContext(Dispatchers.IO) {
            val uid = userId ?: error("Not authenticated")
            val url = get(
                path("Items"),
                "userId" to uid,
                "parentId" to parentId,
                "startIndex" to startIndex.toString(),
                "limit" to limit.toString(),
                "sortBy" to "SortName",
                "sortOrder" to "Ascending",
                "fields" to "Overview",
            )
            val result = json.decodeFromString(ItemsResult.serializer(), exec(url))
            ItemsPage(result.items.map { it.toMediaItem() }, result.totalRecordCount)
        }

    suspend fun itemDetails(itemId: String): MediaItem = withContext(Dispatchers.IO) {
        val uid = userId ?: error("Not authenticated")
        // Request Chapters so the detail/playback seek-preview scrubber has section thumbnails.
        val body = exec(get(path("Users", uid, "Items", itemId), "fields" to "Chapters"))
        json.decodeFromString(BaseItemDto.serializer(), body).toMediaItem()
    }

    /**
     * The episode that plays after [currentEpisodeId] in series [seriesId] (crossing season boundaries),
     * or null if it's the last episode. Uses `/Shows/{seriesId}/Episodes?startItemId=` (which lists the
     * whole series in order starting at the given episode) and picks the following one — see
     * [pickNextEpisodeId] for the (semantics-robust) selection. Chapters are requested so the next
     * episode's seek-preview scrubber has thumbnails immediately.
     */
    suspend fun nextEpisode(seriesId: String, currentEpisodeId: String): MediaItem? = withContext(Dispatchers.IO) {
        val uid = userId ?: return@withContext null
        val url = get(
            path("Shows", seriesId, "Episodes"),
            "userId" to uid,
            "startItemId" to currentEpisodeId,
            "limit" to "2",
            "fields" to "Chapters",
        )
        val items = json.decodeFromString(ItemsResult.serializer(), exec(url)).items
        val nextId = pickNextEpisodeId(items.map { it.id }, currentEpisodeId) ?: return@withContext null
        items.firstOrNull { it.id == nextId }?.toMediaItem()
    }

    /**
     * Skippable media segments (intro/outro/recap/preview) for [itemId] from Jellyfin's Media Segments
     * API (10.10+, populated by the Intro Skipper plugin). Returns empty — never an error — when the
     * server predates the API or the item hasn't been analysed, so playback is unaffected either way.
     */
    override suspend fun mediaSegments(itemId: String): List<MediaSegment> = withContext(Dispatchers.IO) {
        runCatching {
            val url = get(
                path("MediaSegments", itemId),
                "includeSegmentTypes" to "Intro,Outro,Recap,Preview,Commercial",
            )
            json.decodeFromString(MediaSegmentsResult.serializer(), exec(url)).items.mapNotNull { it.toSegment() }
        }.getOrElse { emptyList() }
    }

    /**
     * Set the Jellyfin native watch state for [itemId]: mark **played** (POST) or **unplayed** (DELETE)
     * for the current user. Marking a series/season folder cascades to its child episodes server-side.
     * Throws on an HTTP error (e.g. an expired token) so the caller can recover.
     */
    suspend fun markPlayed(itemId: String, played: Boolean): Unit = withContext(Dispatchers.IO) {
        val uid = userId ?: error("Not authenticated")
        val segments = path("Users", uid, "PlayedItems", itemId)
        exec(if (played) postNoBody(segments) else delete(segments))
        logger.event("library", "Marked item ${if (played) "played" else "unplayed"}")
    }

    suspend fun searchItems(searchTerm: String, limit: Int): ItemsPage =
        withContext(Dispatchers.IO) {
            val uid = userId ?: error("Not authenticated")
            val url = get(
                path("Items"),
                "userId" to uid,
                "searchTerm" to searchTerm,
                "recursive" to "true",
                "includeItemTypes" to "Movie,Series,Episode,Video",
                "limit" to limit.toString(),
                "sortBy" to "SortName",
                "sortOrder" to "Ascending",
                "fields" to "Overview",
            )
            val result = json.decodeFromString(ItemsResult.serializer(), exec(url))
            ItemsPage(result.items.map { it.toMediaItem() }, result.totalRecordCount)
        }

    // ----- JellyfinApi: playback info + upstream resolution -----

    override suspend fun postPlaybackInfo(
        itemId: String,
        deviceProfileJson: String,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxBitrate: Long?,
        startTimeTicks: Long,
        requireTranscode: Boolean,
    ): JellyfinApi.PlaybackInfoResult = withContext(Dispatchers.IO) {
        val uid = userId ?: error("Not authenticated")
        val profileElement = json.parseToJsonElement(deviceProfileJson)
        val body = buildJsonObject {
            put("UserId", uid)
            maxBitrate?.let { put("MaxStreamingBitrate", it) }
            put("StartTimeTicks", startTimeTicks)
            audioStreamIndex?.let { put("AudioStreamIndex", it) }
            subtitleStreamIndex?.let { put("SubtitleStreamIndex", it) }
            put("DeviceProfile", profileElement)
            put("EnableDirectPlay", !requireTranscode)
            put("EnableDirectStream", !requireTranscode)
            put("EnableTranscoding", true)
            put("AllowVideoStreamCopy", true)
            put("AllowAudioStreamCopy", true)
            put("AutoOpenLiveStream", true)
        }
        val response = json.decodeFromString(
            PlaybackInfoResponse.serializer(),
            exec(post(path("Items", itemId, "PlaybackInfo"), body)),
        )
        val source = response.mediaSources.firstOrNull()
            ?: run {
                logger.w("jellyfin", "PlaybackInfo returned no media sources")
                error("PlaybackInfo returned no media sources")
            }
        val msId = source.id ?: itemId
        val resolved = resolveUpstream(itemId, source, response.playSessionId, requireTranscode)
        upstreamCache[msId] = resolved

        // Pick the PRIMARY video stream: the largest real video, ignoring attached cover-art/thumbnail
        // "video" streams (mjpeg/png/…) some files embed — otherwise the reported resolution can be wrong
        // (e.g. a tiny cover instead of the 1080p feature). Fall back to any video stream.
        val video = source.mediaStreams
            .filter { it.type.equals("Video", true) && it.codec?.lowercase() !in IMAGE_CODECS }
            .maxByOrNull { (it.width ?: 0).toLong() * (it.height ?: 0).toLong() }
            ?: source.mediaStreams.firstOrNull { it.type.equals("Video", true) }
        val audio = source.mediaStreams.firstOrNull { it.type.equals("Audio", true) }
        val audioTracks = source.mediaStreams.filter { it.type.equals("Audio", true) }.toMediaTracks()
        val subtitleTracks = source.mediaStreams.filter { it.type.equals("Subtitle", true) }.toMediaTracks()
        logger.event(
            "jellyfin",
            "PlaybackInfo resolved (container=${source.container}, video=${video?.codec} ${video?.width}x${video?.height}, " +
                "audio=${audio?.codec}, bitrateBps=${source.bitrate}, hls=${resolved.isHls}, " +
                "psid present=${response.playSessionId != null}, " +
                "audioTracks=${audioTracks.size}, subtitleTracks=${subtitleTracks.size})",
        )
        JellyfinApi.PlaybackInfoResult(
            mediaSourceId = msId,
            playSessionId = response.playSessionId,
            container = source.container ?: "mp4",
            videoCodec = video?.codec ?: "h264",
            audioCodec = audio?.codec ?: "aac",
            isHdr = video.isHdr(),
            bitDepth = video?.bitDepth ?: 8,
            runtimeSeconds = source.runTimeTicks?.let { it / TICKS_PER_SECOND },
            upstreamUrl = resolved.url,
            contentType = resolved.contentType,
            isHls = resolved.isHls,
            totalLength = resolved.totalLength,
            sourceBitrateBps = source.bitrate,
            videoWidth = video?.width,
            videoHeight = video?.height,
            videoBitrateBps = video?.bitRate,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
        )
    }

    /** Map Jellyfin audio/subtitle [MediaStreamDto]s (with a valid Index) to selectable domain [MediaTrack]s. */
    private fun List<MediaStreamDto>.toMediaTracks(): List<MediaTrack> = mapNotNull { s ->
        val idx = s.index ?: return@mapNotNull null
        val label = s.displayTitle?.takeIf { it.isNotBlank() }
            ?: s.title?.takeIf { it.isNotBlank() }
            ?: s.language?.takeIf { it.isNotBlank() }
            ?: "Track $idx"
        MediaTrack(index = idx, language = s.language, label = label, isDefault = s.isDefault, isForced = s.isForced)
    }

    override fun resolveUpstreamFor(mediaSourceId: String): UpstreamSource =
        upstreamCache[mediaSourceId] ?: error("No resolved upstream for media source")

    private fun resolveUpstream(
        itemId: String,
        source: MediaSourceDto,
        playSessionId: String?,
        requireTranscode: Boolean,
    ): UpstreamSource {
        val canDirect = (source.supportsDirectStream ?: false) || (source.supportsDirectPlay ?: false)
        val transcodingUrl = source.transcodingUrl
        val useTranscode = (requireTranscode || !canDirect) && transcodingUrl != null
        logger.event(
            "jellyfin",
            "Playback decision=${if (useTranscode) "transcode" else "direct"} " +
                "(canDirect=$canDirect, forced=$requireTranscode, container=${source.container})",
        )

        val (rawUrl, isHls, contentType) = when {
            useTranscode -> {
                val hls = source.transcodingSubProtocol.equals("hls", true) ||
                    transcodingUrl.contains(".m3u8")
                Triple(
                    transcodingUrl,
                    hls,
                    if (hls) "application/vnd.apple.mpegurl" else mimeForContainer(source.container),
                )
            }
            source.directStreamUrl != null ->
                Triple(source.directStreamUrl, false, mimeForContainer(source.container))
            else ->
                Triple(directStreamPath(itemId, source.id ?: itemId, playSessionId), false, mimeForContainer(source.container))
        }
        return UpstreamSource(
            url = absoluteUpstreamUrl(rawUrl),
            authHeader = authHeaderValue(),
            contentType = contentType,
            isHls = isHls,
            isTranscoding = useTranscode,
            // Direct play serves a static file whose byte length is Jellyfin's reported MediaSource size,
            // so the proxy can advertise it and the renderer can byte-range seek. A transcode is a live
            // stream of unknown length (source.size is the ORIGINAL file, not the transcode) and is seeked
            // server-side, so leave it null.
            totalLength = if (useTranscode) null else source.size,
        )
    }

    // ----- JellyfinApi: reporting / cleanup -----

    override suspend fun reportPlaying(playSessionId: String?, itemId: String) = report(
        path("Sessions", "Playing"),
        buildJsonObject {
            put("ItemId", itemId)
            playSessionId?.let { put("PlaySessionId", it) }
        },
    )

    override suspend fun reportProgress(playSessionId: String?, itemId: String, positionTicks: Long, isPaused: Boolean) =
        report(
            path("Sessions", "Playing", "Progress"),
            buildJsonObject {
                put("ItemId", itemId)
                playSessionId?.let { put("PlaySessionId", it) }
                put("PositionTicks", positionTicks)
                put("IsPaused", isPaused)
            },
        )

    override suspend fun reportStopped(playSessionId: String?, itemId: String, positionTicks: Long) = report(
        path("Sessions", "Playing", "Stopped"),
        buildJsonObject {
            put("ItemId", itemId)
            playSessionId?.let { put("PlaySessionId", it) }
            put("PositionTicks", positionTicks)
        },
    )

    override suspend fun stopActiveEncoding(playSessionId: String?, deviceId: String) = withContext(Dispatchers.IO) {
        val url = httpUrl(path("Videos", "ActiveEncodings")).newBuilder()
            .addQueryParameter("deviceId", deviceId)
            .apply { playSessionId?.let { addQueryParameter("playSessionId", it) } }
            .build()
        runCatching { exec(Request.Builder().url(url).delete().header("Authorization", authHeaderValue()).build()) }
        Unit
    }

    private suspend fun report(segments: List<String>, body: JsonObject) = withContext(Dispatchers.IO) {
        runCatching { exec(post(segments, body)) }
        Unit
    }

    // ----- HTTP helpers -----

    private fun base(): HttpUrl = (baseUrl ?: error("Server not configured")).toHttpUrl()

    private fun path(vararg segments: String): List<String> = segments.toList()

    private fun httpUrl(segments: List<String>): HttpUrl {
        val b = base().newBuilder()
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun get(segments: List<String>, vararg query: Pair<String, String>): Request {
        val b = base().newBuilder()
        segments.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return Request.Builder().url(b.build())
            .header("Authorization", authHeaderValue())
            .header("Accept", "application/json")
            .get()
            .build()
    }

    private fun post(segments: List<String>, body: JsonObject, vararg query: Pair<String, String>): Request {
        val payload = json.encodeToString(JsonObject.serializer(), body)
        val b = base().newBuilder()
        segments.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return Request.Builder().url(b.build())
            .header("Authorization", authHeaderValue())
            .header("Accept", "application/json")
            .post(payload.toRequestBody(jsonMedia))
            .build()
    }

    private fun postNoBody(segments: List<String>): Request =
        Request.Builder().url(httpUrl(segments))
            .header("Authorization", authHeaderValue())
            .header("Accept", "application/json")
            .post(ByteArray(0).toRequestBody(jsonMedia))
            .build()

    private fun delete(segments: List<String>): Request =
        Request.Builder().url(httpUrl(segments))
            .header("Authorization", authHeaderValue())
            .header("Accept", "application/json")
            .delete()
            .build()

    /** Like [runCatching] but never swallows coroutine cancellation (rethrows [CancellationException]). */
    private inline fun <T> catchingNonCancel(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /**
     * Execute a request with bounded retry so a transient network blip / gateway error self-recovers
     * (browse, auth, playback-info). Retries connection errors and retryable statuses (408/429/5xx)
     * with capped exponential backoff; a 4xx is returned immediately (a 401 means the session expired).
     * Suspends (not blocks) for backoff and cancels the in-flight call on coroutine cancellation, so a
     * logout / cancelled Quick Connect poll aborts promptly instead of hanging on a flapping server.
     */
    private suspend fun exec(request: Request): String {
        var attempt = 0
        while (true) {
            val outcome = runCatching { execOnce(request) }
            outcome.getOrNull()?.let { return it }
            val error = outcome.exceptionOrNull() ?: error("unreachable")
            if (error is CancellationException) throw error
            val retryable = when (error) {
                is JellyfinHttpException -> error.code != 401 && UpstreamRetry.isRetryableStatus(error.code)
                is IOException -> true // connection reset / timeout / DNS blip
                else -> false
            }
            attempt += 1
            if (!retryable || attempt > retryBudget.maxConsecutiveFailures) {
                // Surface a genuine server error (with its parsed, already-redacted reason) in
                // diagnostics regardless of caller — browsing, search, or starting playback. A 401 is
                // routine session-expiry / offline-cache control flow handled by callers, so don't log
                // it as a failure here.
                if (error is JellyfinHttpException && !error.isUnauthorized) {
                    logger.w("jellyfin", "request failed: ${error.message}")
                } else if (error is JellyfinHttpException && error.isUnauthorized) {
                    logger.event("jellyfin", "request unauthorized (status=401) — session expired")
                } else if (error is IOException) {
                    logger.w("jellyfin", "request failed after retries (${error.javaClass.simpleName})", error)
                }
                throw error
            }
            val backoffMs = Backoff.delayMillis(retryBudget, attempt, ThreadLocalRandom.current().nextDouble())
            logger.event("jellyfin", "transient request failure (retry $attempt after ${backoffMs}ms)")
            if (backoffMs > 0) delay(backoffMs)
        }
    }

    /** Run one HTTP request on OkHttp's pool, cancelling the call if the coroutine is cancelled. */
    private suspend fun execOnce(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = httpClient.newCall(request)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // A body-phase failure (read timeout / reset) throws here; OkHttp will NOT call
                // onFailure again, so we must resume the continuation ourselves or the coroutine hangs.
                try {
                    response.use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            val reason = ServerErrorReason.extract(body, resp.header("Content-Type"), json)
                            cont.resumeWithException(JellyfinHttpException(resp.code, reason))
                        } else cont.resume(body)
                    }
                } catch (t: Throwable) {
                    if (!cont.isCancelled) cont.resumeWithException(t)
                }
            }
        })
    }

    private fun directStreamPath(itemId: String, mediaSourceId: String, playSessionId: String?): String {
        val b = base().newBuilder()
            .addPathSegment("Videos").addPathSegment(itemId).addPathSegment("stream")
            .addQueryParameter("static", "true")
            .addQueryParameter("mediaSourceId", mediaSourceId)
            .addQueryParameter("deviceId", deviceId)
        accessToken?.let { b.addQueryParameter("api_key", it) }
        playSessionId?.let { b.addQueryParameter("playSessionId", it) }
        return b.build().toString()
    }

    private fun absoluteUpstreamUrl(rawUrl: String): String {
        val absolute = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            base().resolve(rawUrl)?.toString() ?: (baseUrl!!.trimEnd('/') + rawUrl)
        }
        // Defense-in-depth: when the server itself is reached over HTTPS, never fetch the
        // token-bearing upstream over cleartext — a misbehaving/compromised server must not be able
        // to downgrade the token (api_key / Authorization) onto an http connection.
        if (base().isHttps && absolute.startsWith("http://")) {
            throw IllegalStateException("Refusing to stream over cleartext from an HTTPS server.")
        }
        return absolute
    }

    private fun authHeaderValue(): String = buildString {
        append("MediaBrowser ")
        append("Client=\"").append(clientName).append("\", ")
        append("Device=\"").append(deviceName).append("\", ")
        append("DeviceId=\"").append(deviceId).append("\", ")
        append("Version=\"").append(appVersion).append("\"")
        accessToken?.let { append(", Token=\"").append(it).append("\"") }
    }

    private fun mimeForContainer(container: String?): String = when (container?.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ogv" -> "video/ogg"
        else -> "video/mp4"
    }

    private fun BaseItemDto.toMediaItem(): MediaItem {
        // For library views CollectionType ("movies"/"music"/…) is the meaningful kind used to filter
        // video libraries; for items (Movie/Series/Season/Episode) CollectionType is null so Type wins.
        val kind = collectionType ?: type
        val folder = isFolder ?: (collectionType != null || type in FOLDER_TYPES)
        return MediaItem(
            id = id,
            title = name ?: "(untitled)",
            year = productionYear,
            runtimeSeconds = runTimeTicks?.let { it / TICKS_PER_SECOND },
            overview = overview,
            resumePositionSeconds = userData?.playbackPositionTicks
                ?.takeIf { it > 0 }?.let { it / TICKS_PER_SECOND },
            isFolder = folder,
            type = kind,
            seriesId = seriesId,
            subtitle = episodeSubtitle(),
            imageTag = imageTags?.get("Primary"),
            chapters = chapters?.map {
                MediaChapter(
                    startSeconds = it.startPositionTicks / TICKS_PER_SECOND,
                    name = it.name,
                    imageTag = it.imageTag,
                )
            }.orEmpty(),
            played = userData?.played ?: false,
            unplayedItemCount = userData?.unplayedItemCount,
            playedPercentage = userData?.playedPercentage,
        )
    }

    private fun BaseItemDto.episodeSubtitle(): String? = when {
        type.equals("Episode", true) -> buildString {
            seriesName?.let { append(it) }
            if (parentIndexNumber != null && indexNumber != null) {
                if (isNotEmpty()) append(" · ")
                append("S").append(parentIndexNumber).append(" E").append(indexNumber)
            }
        }.ifBlank { null }
        else -> null
    }

    private fun MediaStreamDto?.isHdr(): Boolean {
        if (this == null) return false
        val range = videoRangeType ?: videoRange
        return range != null && !range.equals("SDR", true)
    }

    companion object {
        private val FOLDER_TYPES = setOf("CollectionFolder", "Series", "Season", "BoxSet", "Folder", "UserView")
        // Codecs used for attached cover-art/thumbnail streams (not the real video feed).
        private val IMAGE_CODECS = setOf("mjpeg", "png", "gif", "bmp", "jpeg", "jpg", "webp")
    }
}

class JellyfinHttpException(val code: Int, val serverReason: String? = null) :
    RuntimeException("Jellyfin request failed with HTTP $code" + (serverReason?.let { " ($it)" } ?: "")) {
    /** A 401 means the stored access token was revoked/expired — the user must re-authenticate. */
    val isUnauthorized: Boolean get() = code == 401
}

/**
 * A pending Quick Connect handshake. [code] is shown to the user to approve on their Jellyfin server;
 * [secret] is used to poll and must never be logged or surfaced to the TV.
 */
data class QuickConnectHandshake(val secret: String, val code: String)

// ----- DTOs (lenient; PascalCase from the documented Jellyfin JSON) -----

@Serializable
private data class QuickConnectResultDto(
    @SerialName("Authenticated") val authenticated: Boolean = false,
    @SerialName("Secret") val secret: String? = null,
    @SerialName("Code") val code: String? = null,
)

@Serializable
private data class PublicSystemInfo(
    @SerialName("Version") val version: String? = null,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
private data class AuthenticationResult(
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("User") val user: UserDto? = null,
)

@Serializable
private data class UserDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
)

@Serializable
private data class ItemsResult(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

@Serializable
private data class MediaSegmentsResult(
    @SerialName("Items") val items: List<MediaSegmentDto> = emptyList(),
)

@Serializable
private data class MediaSegmentDto(
    @SerialName("Type") val type: String? = null,
    @SerialName("StartTicks") val startTicks: Long? = null,
    @SerialName("EndTicks") val endTicks: Long? = null,
) {
    /** To a domain [MediaSegment] (ticks -> seconds), or null if the range is missing/degenerate. */
    fun toSegment(): MediaSegment? {
        val start = (startTicks ?: return null) / TICKS_PER_SECOND
        val end = (endTicks ?: return null) / TICKS_PER_SECOND
        if (end <= start) return null
        return MediaSegment(SegmentType.fromApi(type), start, end)
    }
}

/**
 * Pick the next episode's id from the ordered ids returned by `/Shows/{id}/Episodes?startItemId=`
 * (which lists the series from [currentId] onward). Robust to whether the server treats `startItemId`
 * as inclusive (list is `[current, next, ...]`) or exclusive (`[next, ...]`): if the first id is the
 * current episode, the next is the one after it; otherwise the first returned id already IS the next.
 * Returns null when there is no following episode (the current one is last). Pure + unit-tested.
 */
internal fun pickNextEpisodeId(orderedIds: List<String>, currentId: String): String? =
    if (orderedIds.firstOrNull() == currentId) orderedIds.getOrNull(1) else orderedIds.firstOrNull()

@Serializable
private data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("UserData") val userData: UserItemDataDto? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String>? = null,
    @SerialName("Chapters") val chapters: List<ChapterInfoDto>? = null,
)

@Serializable
private data class ChapterInfoDto(
    @SerialName("StartPositionTicks") val startPositionTicks: Long = 0,
    @SerialName("Name") val name: String? = null,
    @SerialName("ImageTag") val imageTag: String? = null,
)

@Serializable
private data class UserItemDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    /** True when the item is fully watched (Jellyfin native watch state). */
    @SerialName("Played") val played: Boolean? = null,
    /** For folders (series/season), how many child items are still unwatched. */
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
    /** 0..100 watched fraction of a single item, when the server reports it. */
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
)

@Serializable
private data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable
private data class MediaSourceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean? = null,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean? = null,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("TranscodingSubProtocol") val transcodingSubProtocol: String? = null,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

@Serializable
private data class MediaStreamDto(
    @SerialName("Type") val type: String? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("BitDepth") val bitDepth: Int? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("VideoRange") val videoRange: String? = null,
    @SerialName("Index") val index: Int? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("BitRate") val bitRate: Long? = null,
)

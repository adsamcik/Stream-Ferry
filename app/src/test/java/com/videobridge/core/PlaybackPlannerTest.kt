package com.videobridge.core.transcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers [PlaybackPlanner] — the pure, ordered playback plan (best band first; engine priority). */
class PlaybackPlannerTest {

    private val planner = PlaybackPlanner()

    // A TV that plays everything at 4K, 10-bit, HLS.
    private val fullTv = ReceiverPlaybackCapabilities(
        h264MaxResolution = ResolutionTier.FHD_1080P,
        hevcMaxResolution = ResolutionTier.UHD_4K,
        vp9MaxResolution = ResolutionTier.UHD_4K,
        av1MaxResolution = ResolutionTier.UHD_4K,
        tenBit = true,
        supportsFmp4 = true,
        supportsTs = true,
    )
    // A TV that only plays H.264 up to 1080p (the conservative default).
    private val h264OnlyTv = ReceiverPlaybackCapabilities()

    // A phone that HW-encodes H.264 + HEVC at 4K; can SW-encode H.264 up to 1080p.
    private val device = DeviceEncodeCapabilities(
        h264MaxResolution = ResolutionTier.UHD_4K,
        hevcMaxResolution = ResolutionTier.UHD_4K,
        softwareCodecs = setOf(VideoCodec.H264),
        softwareMaxResolution = ResolutionTier.FHD_1080P,
    )
    private val allEngines = EngineAvailability(server = true, clientHardware = true, clientCpu = true)

    private fun hevc4kSource(canDirectPlay: Boolean) = PlannerSource(
        codec = VideoCodec.HEVC, tier = ResolutionTier.UHD_4K, tenBit = true,
        bitrateBps = 60_000_000, canDirectPlay = canDirectPlay,
    )

    @Test fun directPlayIsFirstWhenTvCanPlayOriginal() {
        val plan = planner.plan(hevc4kSource(canDirectPlay = true), fullTv, device, allEngines)
        assertEquals(PlaybackEngineKind.DIRECT, plan.first().engine)
        assertEquals(QualityBand.NATIVE, plan.first().band)
    }

    @Test fun noDirectWhenTvCannotPlayOriginal() {
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, allEngines)
        assertTrue(plan.none { it.engine == PlaybackEngineKind.DIRECT })
        assertEquals(QualityBand.MAX_TRANSCODE, plan.first().band)
    }

    @Test fun preferDirectFalseSkipsDirect() {
        val plan = planner.plan(hevc4kSource(canDirectPlay = true), fullTv, device, allEngines, preferDirect = false)
        assertTrue(plan.none { it.engine == PlaybackEngineKind.DIRECT })
    }

    @Test fun serverIsPreferredOverOnDeviceWithinABand() {
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, allEngines)
        val maxBand = plan.filter { it.band == QualityBand.MAX_TRANSCODE }
        // First engine tried for the top band is the server.
        assertEquals(PlaybackEngineKind.SERVER, maxBand.first().engine)
        // Engine order within the band is server -> HW -> CPU.
        val engineOrder = maxBand.map { it.engine }
        assertEquals(engineOrder.sortedBy { listOf(PlaybackEngineKind.SERVER, PlaybackEngineKind.CLIENT_HW, PlaybackEngineKind.CLIENT_CPU).indexOf(it) }, engineOrder)
    }

    @Test fun bestCodecChosenForTopBand() {
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, allEngines)
        // Server MAX band should pick AV1 (most efficient the TV can play).
        val serverMax = plan.first { it.band == QualityBand.MAX_TRANSCODE && it.engine == PlaybackEngineKind.SERVER }
        assertEquals(VideoCodec.AV1, serverMax.codec)
    }

    @Test fun standardAndLowBandsPinToH264Floor() {
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, allEngines)
        val floorBands = plan.filter { it.band == QualityBand.STANDARD || it.band == QualityBand.LOW }
        assertTrue(floorBands.isNotEmpty())
        assertTrue(floorBands.all { it.codec == VideoCodec.H264 })
    }

    @Test fun onlyAvailableEnginesAppear() {
        val serverOnly = EngineAvailability(server = true, clientHardware = false, clientCpu = false)
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, serverOnly)
        assertTrue(plan.all { it.engine == PlaybackEngineKind.SERVER })
    }

    @Test fun clientHardwareGatedByDeviceEncodeSupport() {
        // Device with NO HEVC HW encoder: on-device HEVC must not appear (only H.264 HW).
        val h264HwOnly = DeviceEncodeCapabilities(h264MaxResolution = ResolutionTier.UHD_4K, hevcMaxResolution = null)
        val noServer = EngineAvailability(server = false, clientHardware = true, clientCpu = false)
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, h264HwOnly, noServer)
        assertTrue(plan.all { it.engine == PlaybackEngineKind.CLIENT_HW })
        assertTrue(plan.all { it.codec == VideoCodec.H264 })
    }

    @Test fun cpuBandBoundedToSoftwareTierAndCodecs() {
        // Only CPU engine, device SW-encodes H.264 up to 1080p: no 4K CPU attempt, H.264 only.
        val cpuOnly = EngineAvailability(server = false, clientHardware = false, clientCpu = true)
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, cpuOnly)
        assertTrue(plan.all { it.engine == PlaybackEngineKind.CLIENT_CPU })
        assertTrue(plan.all { it.codec == VideoCodec.H264 })
        assertTrue(plan.all { it.tier.maxHeightPx <= ResolutionTier.FHD_1080P.maxHeightPx })
    }

    @Test fun h264OnlyTvGetsH264Ladder() {
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), h264OnlyTv, device, allEngines)
        assertTrue(plan.all { it.codec == VideoCodec.H264 })
        assertTrue(plan.all { it.tier.maxHeightPx <= ResolutionTier.FHD_1080P.maxHeightPx }) // H.264 capped at 1080p
        assertTrue(plan.all { !it.tenBit })
    }

    @Test fun tenBitOnlyWhenTvAndCodecSupport() {
        // 10-bit HEVC source, TV supports 10-bit: MAX server attempt keeps 10-bit on a non-H.264 codec.
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, allEngines)
        val serverMax = plan.first { it.band == QualityBand.MAX_TRANSCODE && it.engine == PlaybackEngineKind.SERVER }
        assertTrue(serverMax.tenBit)
        assertTrue(serverMax.codec != VideoCodec.H264)
    }

    @Test fun bandsAreOrderedBestFirst() {
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, allEngines)
        val bandRank = QualityBand.entries.withIndex().associate { (i, b) -> b to i }
        val ranks = plan.map { bandRank.getValue(it.band) }
        assertEquals(ranks.sorted(), ranks) // non-decreasing band rank == best-first
    }

    @Test fun emptyWhenNothingPossible() {
        val nothing = EngineAvailability(server = false, clientHardware = false, clientCpu = false)
        val plan = planner.plan(hevc4kSource(canDirectPlay = false), fullTv, device, nothing)
        assertTrue(plan.isEmpty())
    }
}

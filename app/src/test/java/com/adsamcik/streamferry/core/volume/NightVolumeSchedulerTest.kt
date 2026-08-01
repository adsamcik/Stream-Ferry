package com.adsamcik.streamferry.core.volume

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightVolumeSchedulerTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun instant(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 8, day, hour, minute, 0, 0, zone).toInstant()
    private fun input(now: Instant, volume: Float = .8f, manual: Boolean = false) =
        NightVolumeInput(now, zone, activePlayback = true, volumeSupported = true, effectiveVolume = volume, manualVolumeAdjusted = manual)

    @Test fun gradualInterpolatesFromCapturedSessionVolume() {
        val policy = NightVolumePolicy.Gradual(LocalTime.of(22, 0), LocalTime.of(2, 0), .4f)
        val started = NightVolumeScheduler.evaluate(policy, input(instant(1, 22)))
        val halfway = NightVolumeScheduler.evaluate(policy, input(instant(2, 0)), started.session)

        assertEquals(.6f, halfway.commandVolume!!, .001f)
    }

    @Test fun gradualCommandsAreSparseAcrossRepeatedPolls() {
        val policy = NightVolumePolicy.Gradual(LocalTime.of(22, 0), LocalTime.of(0, 0), .4f)
        val started = NightVolumeScheduler.evaluate(policy, input(instant(1, 22)))
        val first = NightVolumeScheduler.evaluate(policy, input(instant(1, 23)), started.session)
        val repeated = NightVolumeScheduler.evaluate(policy, input(instant(1, 23, 1)), first.session)

        assertEquals(.6f, first.commandVolume!!, .001f)
        assertNull(repeated.commandVolume)
    }

    @Test fun hardPolicyIssuesOneReductionAndDoesNotDependOnProtocol() {
        val policy = NightVolumePolicy.Hard(LocalTime.of(22, 0), .5f)
        val waiting = NightVolumeScheduler.evaluate(policy, input(instant(1, 21, 30)))
        val first = NightVolumeScheduler.evaluate(policy, input(instant(1, 22)), waiting.session)
        val repeated = NightVolumeScheduler.evaluate(policy, input(instant(1, 22, 1)), first.session)

        assertEquals(.5f, first.commandVolume)
        assertNull(repeated.commandVolume)
    }

    @Test fun hardPolicyReducesWhenAnExistingSessionCrossesMidnight() {
        val policy = NightVolumePolicy.Hard(LocalTime.MIDNIGHT, .5f)
        val beforeMidnight = NightVolumeScheduler.evaluate(policy, input(instant(1, 23, 59)))
        val afterMidnight = NightVolumeScheduler.evaluate(policy, input(instant(2, 0, 1)), beforeMidnight.session)

        assertNull(beforeMidnight.commandVolume)
        assertEquals(.5f, afterMidnight.commandVolume)
    }
    @Test fun automaticPolicyNeverIncreasesVolume() {
        val policy = NightVolumePolicy.Hard(LocalTime.of(22, 0), .9f)
        assertNull(NightVolumeScheduler.evaluate(policy, input(instant(1, 22, 1), volume = .6f)).commandVolume)
    }

    @Test fun manualVolumeAdjustmentSuspendsAutomationForSession() {
        val policy = NightVolumePolicy.Hard(LocalTime.of(22, 0), .4f)
        val manual = NightVolumeScheduler.evaluate(policy, input(instant(1, 22, 1), manual = true))
        val later = NightVolumeScheduler.evaluate(policy, input(instant(1, 22, 2)), manual.session)

        assertTrue(manual.session.automaticChangesSuspended)
        assertNull(later.commandVolume)
    }

    @Test fun gradualWindowCrossesMidnight() {
        val policy = NightVolumePolicy.Gradual(LocalTime.of(22, 0), LocalTime.of(6, 0), .4f)
        val started = NightVolumeScheduler.evaluate(policy, input(instant(1, 22)))
        val twoAm = NightVolumeScheduler.evaluate(policy, input(instant(2, 2)), started.session)

        assertEquals(.6f, twoAm.commandVolume!!, .001f)
    }

    @Test fun dstGapUsesInstantBasedInterpolationDeterministically() {
        val policy = NightVolumePolicy.Gradual(LocalTime.of(1, 0), LocalTime.of(4, 0), .4f)
        val start = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, zone).toInstant()
        val afterGap = ZonedDateTime.of(2026, 3, 29, 3, 0, 0, 0, zone).toInstant()
        val started = NightVolumeScheduler.evaluate(policy, input(start))
        val result = NightVolumeScheduler.evaluate(policy, input(afterGap), started.session)

        assertEquals(.6f, result.commandVolume!!, .001f)
    }

    @Test fun dstOverlapUsesTheEarlierOffsetDeterministically() {
        val policy = NightVolumePolicy.Gradual(LocalTime.of(1, 0), LocalTime.of(4, 0), .4f)
        val start = ZonedDateTime.of(2026, 10, 25, 1, 0, 0, 0, zone).toInstant()
        val overlap = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, zone).toInstant()
        val started = NightVolumeScheduler.evaluate(policy, input(start))
        val result = NightVolumeScheduler.evaluate(policy, input(overlap), started.session)

        assertEquals(.65f, result.commandVolume!!, .001f)
    }

    @Test fun inactiveOrUnsupportedPlaybackNeverReceivesACommand() {
        val policy = NightVolumePolicy.Hard(LocalTime.of(22, 0), .4f)
        val after = instant(1, 22, 1)
        val session = NightVolumeSession(startedAt = instant(1, 21), startingVolume = .8f)

        assertNull(NightVolumeScheduler.evaluate(policy, input(after).copy(activePlayback = false), session).commandVolume)
        assertNull(NightVolumeScheduler.evaluate(policy, input(after).copy(volumeSupported = false), session).commandVolume)
    }
}

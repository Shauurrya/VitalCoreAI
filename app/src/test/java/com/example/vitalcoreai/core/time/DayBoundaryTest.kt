package com.example.vitalcoreai.core.time

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.time.ZoneId

/**
 * T-11 — the day-boundary and travel cases the migration exists to make safe.
 *
 * The pair of tests the handoff names specifically:
 *  - travel across a zone boundary must not corrupt a day key;
 *  - a clock crossing midnight must actually fire the day-boundary logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayBoundaryTest {

    @After
    fun tearDown() = VitalTime.reset()

    /**
     * The handoff's travel case, stated precisely.
     *
     * A day key is a *local* epoch day, so at 22:00 in New York it is already tomorrow in
     * Kolkata — the key is expected to advance. What must never happen is the instant moving,
     * or a previously stored day being reinterpreted: `epochDayOf` on a fixed instant is
     * zone-dependent by definition, which is exactly why every write goes through one place.
     */
    @Test
    fun `travel advances the local day without moving the instant`() {
        val clock = FixedVitalClock(
            LocalDateTime.of(2026, 5, 10, 22, 0), ZoneId.of("America/New_York")
        )
        VitalTime.clock = clock

        val instantBefore = VitalTime.nowMs()
        val dayNY = VitalTime.todayEpochDay()

        clock.travelTo(ZoneId.of("Asia/Kolkata"))
        val dayIndia = VitalTime.todayEpochDay()

        assertEquals("the instant must not move", instantBefore, VitalTime.nowMs())
        assertEquals("22:00 in New York is already the next day in Kolkata", dayNY + 1, dayIndia)
    }

    /**
     * Travelling WEST at the same instant must not silently rewind the day either — the
     * failure mode that would overwrite yesterday's stored row with today's numbers.
     */
    @Test
    fun `travelling west reports the earlier local day, and start of day follows it`() {
        val clock = FixedVitalClock(
            LocalDateTime.of(2026, 5, 10, 3, 0), ZoneId.of("Asia/Kolkata")
        )
        VitalTime.clock = clock
        val dayIndia = VitalTime.todayEpochDay()

        clock.travelTo(ZoneId.of("America/New_York"))
        val dayNY = VitalTime.todayEpochDay()

        assertEquals("03:00 in Kolkata is still the previous day in New York", dayIndia - 1, dayNY)
        // And the bounds must be recomputed in the new zone rather than cached from the old.
        assertTrue(VitalTime.startOfDayMs(dayNY) < VitalTime.nowMs())
        assertTrue(VitalTime.endOfDayExclusiveMs(dayNY) > VitalTime.nowMs())
    }

    @Test
    fun `advancing a clock from 23 59 to 00 01 rolls the day exactly once`() {
        val clock = FixedVitalClock(
            LocalDateTime.of(2026, 6, 15, 23, 59), ZoneId.of("Europe/London")
        )
        VitalTime.clock = clock
        val before = VitalTime.todayEpochDay()
        assertEquals(23 * 60 + 59, VitalTime.nowMinuteOfDay())

        clock.advanceMinutes(2)

        assertEquals(before + 1, VitalTime.todayEpochDay())
        assertEquals(1, VitalTime.nowMinuteOfDay())
    }

    /** A day key must survive the round trip through its own millisecond bounds. */
    @Test
    fun `day bounds round-trip on a DST transition day`() {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 3, 29, 12, 0), ZoneId.of("Europe/London")
        )
        val day = VitalTime.todayEpochDay()

        assertEquals(1380, VitalTime.lengthOfDayMinutes(day))
        assertEquals(day, VitalTime.epochDayOf(VitalTime.startOfDayMs(day)))
        assertEquals(day, VitalTime.epochDayOf(VitalTime.endOfDayExclusiveMs(day) - 1))
        assertEquals(day + 1, VitalTime.epochDayOf(VitalTime.endOfDayExclusiveMs(day)))
        assertEquals(VitalTime.startOfDayMs(day + 1), VitalTime.endOfDayExclusiveMs(day))
    }

    // ─── dayFlow (F-03) ──────────────────────────────────────────────────────

    @Test
    fun `dayFlow emits the current day immediately`() = runBlocking {
        VitalTime.clock = FixedVitalClock(
            LocalDateTime.of(2026, 6, 15, 9, 0), ZoneId.of("UTC")
        )
        // first() cancels the producer after one value, so the default 60-second poll
        // interval is never actually waited on.
        val today = withTimeout(2_000) { dayFlow().first() }
        assertEquals(VitalTime.todayEpochDay(), today)
    }

    /**
     * The F-03 fix itself: the flow re-emits when the day rolls, and stays quiet while it
     * has not.
     *
     * Uses **real** time on a 25 ms interval rather than a virtual clock, because
     * `dayFlow` deliberately pins its producer to `Dispatchers.Default` — see the note in
     * `DayTicker.kt`. Under a virtual clock the loop's `delay` is skipped, so it spins
     * instead of pausing; that is precisely the failure this arrangement prevents, and a
     * test driving it virtually would be testing something the production flow never does.
     */
    @Test
    fun `dayFlow re-emits only when the day actually changes`() = runBlocking {
        val clock = FixedVitalClock(
            LocalDateTime.of(2026, 6, 15, 23, 30), ZoneId.of("UTC")
        )
        VitalTime.clock = clock

        val seen = CopyOnWriteArrayList<Long>()
        val job = launch(Dispatchers.Default) {
            dayFlow(pollIntervalMs = 25L).collect { seen += it }
        }

        // The current day arrives without waiting for a poll.
        withTimeout(2_000) { while (seen.isEmpty()) delay(5) }
        assertEquals(1, seen.size)

        // Several polls on the same day must stay silent — otherwise every ViewModel would
        // restart its Room query on every tick, forever.
        delay(150)
        assertEquals("a poll within the same day must be silent", 1, seen.size)

        clock.advanceMinutes(45)   // 23:30 → 00:15
        withTimeout(2_000) { while (seen.size < 2) delay(5) }

        assertEquals("crossing midnight must emit exactly once more", 2, seen.size)
        assertEquals(seen[0] + 1, seen[1])
        assertNotEquals(seen[0], seen[1])

        job.cancelAndJoin()
    }
}
